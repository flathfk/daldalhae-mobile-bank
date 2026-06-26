# 5차 해커톤 — 달달해 카페 충전 시스템 자기화

> 작성: 임소라 / 2026.06.26
> 
> 환경: GCP VM 단일 인스턴스, Cloudflare Tunnel로 외부 HTTPS 노출

도메인: https://centered-cheaper-proposal-blades.trycloudflare.com/

## 1. 분산 아키텍처 및 자기화 요약

### 선정 주제

**Nginx·Redis 기반 분산 카페 충전·결제 시스템 "달달해"**

mobile-bank 은행 예제를 카페 충전금 서비스로 자기화했다. 사용자는 카페 지갑에 충전금을 충전하고, 음료를 결제하고, 친구에게 기프티콘을 선물한다. 적립/스탬프(개수) 방식이 아니라 충전금(금액) 기반 서비스로 컨셉을 잡았다.

### 네트워크 포트 매핑 명세

| 서비스 | 포트 | 역할 |
|---|---|---|
| Nginx | 3000 | 대표 접속 포트 (역방향 프록시) |
| frontend-admin | 3001 | PC 관리자 화면 |
| frontend-mobile-view | 3002 | 모바일 조회 화면 |
| frontend-mobile-action | 3003 | 모바일 충전·결제 화면 (Next.js `basePath: "/action"`) |
| backend-spring-api | 3004 | 백엔드 API (Spring Boot + Spring Security + JWT) |
| MariaDB | 3306 | 영속 데이터 (users / accounts / transaction_records) |
| Redis | 6379 | 세션 / 캐시 / 감사 로그 / 최근 선물 대상 |

### 용어 치환 기록

| 은행 용어 | 달달해 용어 | 적용 위치 |
|---|---|---|
| 계좌 (Account) | 지갑 | 화면 전체 |
| 잔액 (balance) | 충전금 | 화면 전체 (admin "총 잔액"/"초기 잔액"/테이블 헤더 누락분 포함) |
| 입금 (deposit) | 충전 | 화면 + `BankService.deposit()` 응답 메시지("충전 완료") |
| 출금 (withdraw) | 결제 | 화면 + `BankService.withdraw()` 응답 메시지("결제 완료") |
| 송금 (transfer) | 선물 / 기프티콘 선물 | 화면 + `BankService.transfer()` 응답 메시지("선물 완료") |
| 다중 송금 (multi-transfer) | 여러 명에게 선물 | 화면 + `BankService.multiTransfer()` 응답 메시지("여러 명에게 선물 완료") |

※ Java 파일명·클래스명·API 경로(`/api/bank/deposit` 등)·`TransactionType` enum 값(`DEPOSIT`/`WITHDRAW`/`TRANSFER_OUT`/`TRANSFER_IN`)은 그대로 두고, 화면 출력 문구와 서비스 응답 메시지 문자열만 치환했다.

### 수정 파일 목록

- `frontend-admin/app/page.jsx`, `frontend-mobile-view/app/page.jsx`, `frontend-mobile-action/app/page.jsx` — UI 문구 전체 치환, 거래 타입 한글 라벨링(`txLabel`), 세션 만료 안내 UI 추가
- `frontend-*/app/layout.jsx` — 탭 타이틀 "달달해 ~"
- `BankService.java` — 결제 최소 금액 제약(if문) + 응답 메시지 자기화
- `DataInitializer.java` — 카페 컨셉 회원·충전금·메뉴(아메리카노/카페라떼/바닐라라떼) 거래내역 시드 데이터


## 2. 분산 데이터 흐름 및 캐시 검증

### Nginx 라우팅 의도

`/etc/nginx/nginx.conf`에서 `map $http_user_agent`로 PC/모바일을 가르고, `location` 블록으로 `/api`와 `/action`을 분리한다.

```nginx
map $http_user_agent $frontend_upstream {
    default http://127.0.0.1:3001;
    ~*(android|iphone|ipad|ipod|mobile) http://127.0.0.1:3002;
}

server {
    listen 3000 default_server;

    location /api/ {
        proxy_pass http://127.0.0.1:3004;
        ...
    }

    location = /action { proxy_pass http://127.0.0.1:3003; ... }
    location /action/  { proxy_pass http://127.0.0.1:3003; ... }

    location / {
        proxy_pass $frontend_upstream;
        ...
    }
}
```

`frontend-mobile-action`은 Next.js `basePath: "/action"`을 쓰기 때문에 경로 prefix를 벗기지 않고 그대로 3003에 전달해야 정적 자산(`_next/*`)까지 맞물려 깨지지 않는다.

**실제 분기 테스트 (User-Agent / 경로별 응답)** — 대표 포트 3000으로 직접 호출해 확인

```
$ curl -s -A "Mozilla Windows"      http://localhost:3000/      | grep title
<title>달달해 관리자</title>            ← PC UA → 3001(admin)

$ curl -s -A "Mozilla iPhone Mobile" http://localhost:3000/      | grep title
<title>달달해 모바일 조회</title>        ← Mobile UA → 3002(view)

$ curl -s                            http://localhost:3000/action | grep title
<title>달달해 결제</title>              ← /action 경로 → 3003(action)

$ curl -s                            http://localhost:3000/api/health
{"service":"mobile-bank-api","status":"OK"}   ← /api 경로 → 3004(backend)
```

같은 대표 포트(3000)·같은 루트(`/`) 요청이라도 User-Agent에 따라 admin(PC) / view(Mobile)로 분기되는 것을 직접 확인했다.

### Redis 캐시 무효화 (cache:account:*)

`RedisStateService`가 지갑 조회 결과를 30초 TTL로 캐싱하고, `BankService`의 충전/결제/선물 로직이 끝나면 `redis.evictAccount(userId)`로 해당 캐시를 즉시 삭제한다. user1(accountId=2, 초기 충전금 50,000원)으로 실제 추적한 흐름:

```
[1] 조회 1회차 — 캐시 생성
$ curl /api/bank/account → {"accountId":2,"balance":50000.00,"status":"ACTIVE"}
$ redis-cli GET cache:account:2
"{\"accountId\":2,\"accountNumber\":\"110-100-000001\",\"balance\":50000.00,\"status\":\"ACTIVE\"}"
$ redis-cli TTL cache:account:2
(integer) 30

[2] 충전 5,000원 — 캐시 무효화(evict) 트리거
$ curl -X POST /api/bank/deposit {"amount":5000}
  → {"message":"충전 완료","account":{"balance":55000.00}}
$ redis-cli EXISTS cache:account:2
(integer) 0      ← 캐시가 즉시 삭제됨

[3] 조회 2회차 — DB에서 다시 읽어 최신값으로 재캐싱
$ curl /api/bank/account → {"accountId":2,"balance":55000.00,"status":"ACTIVE"}
$ redis-cli GET cache:account:2
"{...,\"balance\":55000.00,\"status\":\"ACTIVE\"}"   ← 55,000원으로 재캐싱
```

조회 → 캐시 적중(HIT) → 충전금 변경 → 캐시 삭제(evict) → 다음 조회 시 DB에서 다시 읽어 최신값으로 재캐싱되는 흐름을 확인했다. 프론트는 React Query의 `invalidateQueries(["account"])` / `["transactions"]`를 같은 시점에 호출해 화면도 함께 갱신된다(`useBankMutation` 참고).

### 커스텀 비즈니스 규칙

기존 비관적 잠금(`PESSIMISTIC_WRITE`) 구간은 그대로 두고, 잠금을 잡은 뒤 충전금 차감 전에 카페 도메인 규칙만 조건문으로 추가했다.

```java
// BankService.java - withdraw()
@Transactional
public BankResult withdraw(Long userId, MoneyRequest r) {
    Account a = primaryLocked(userId);          // PESSIMISTIC_WRITE 잠금 (기존 로직, 변경 없음)
    assertActive(a);
    // 카페 도메인 규칙: 최소 결제 금액 1,500원 미만은 결제 불가
    if (r.amount().compareTo(new java.math.BigDecimal("1500")) < 0)
        throw new IllegalArgumentException("최소 결제 금액은 1,500원입니다.");
    ensureBalance(a, r.amount());
    a.withdraw(r.amount());
    ...
}
```

**실제 호출 검증** (user1, accountId=2)

```
$ curl -X POST /api/bank/withdraw {"amount":1000}   ← 최소 금액 미만
  → {"message":"최소 결제 금액은 1,500원입니다."}     ← 거절

$ curl -X POST /api/bank/withdraw {"amount":2000}   ← 최소 금액 이상
  → {"message":"결제 완료","account":{"balance":53000.00}}   ← 정상 차감
```

잠금 안에서 도메인 규칙만 검사하므로 동시성 제어(비관적 잠금)를 해치지 않는다.


## 3. 분산 환경 트러블슈팅

### (1) Redis 세션 만료로 관리자 대시보드가 전부 "0"으로 보이는 문제

**증상**: 관리자 화면에서 회원/지갑/거래 수치가 모두 0으로 표시되고, 지갑 생성 버튼만 `HTTP 403` 에러를 띄움. 백엔드 코드는 정상인데 화면만 깨진 것처럼 보임.

**원인 분석**:
- `application.properties`의 `app.jwt.expiration-seconds=3600`과 `RedisStateService.createSession()`의 `Duration.ofHours(1)` — JWT와 Redis 세션 키(`auth:session:{sessionId}`)가 모두 **1시간**이면 만료된다.
- 관리자 로그인 후 1시간이 지나도록 브라우저에는 만료된 토큰이 `localStorage`(zustand persist)에 그대로 남아있었다.
- `JwtAuthenticationFilter`는 토큰 파싱 실패(만료 포함) 시 예외를 캐치하고 `SecurityContext`만 비운 채 다음 필터로 넘긴다. 인증 객체가 없는 상태로 `/api/admin/**`에 도달하면 `SecurityConfig`의 `.hasRole("ADMIN")` 규칙에 막혀 **403**이 떨어진다. (401이 아니라 403인 이유: 별도 `AuthenticationEntryPoint`를 설정하지 않아 Spring Security 기본 동작이 403으로 처리)
- 같은 만료 토큰으로 대시보드 GET도 403이 났지만, 프론트(`useAdminDashboard`)가 쿼리 에러를 화면에 노출하지 않고 빈 값을 렌더링해서 "0건"처럼 보였다.

**검증**: 무인증 상태로 직접 호출해 동일하게 403이 재현됨을 확인.

```
$ curl -i http://localhost:3004/api/admin/dashboard        # Authorization 헤더 없음
HTTP/1.1 403
```

반대로 새로 로그인해 받은 신선한 토큰으로는 동일 API가 200과 실제 데이터를 정상 반환했다.

**해결**: 세션 만료 자체는 설계된 동작(1시간 TTL)이라 백엔드는 건드리지 않고, 프론트 3개 화면에 "세션이 만료되어 데이터를 불러오지 못했습니다 → 다시 로그인" 안내 배너를 추가해 조용한 실패를 명확한 안내로 바꿨다. (`isError` 조건부 렌더링, 기존 CSS 클래스만 재사용)

### (2) Next.js `basePath`로 인한 라우팅 경로 유실

**증상**: `frontend-mobile-action`을 재시작한 뒤 `curl http://localhost:3003/`이 200이 아니라 404로 응답해 서버가 죽은 것처럼 보임.

**원인**: `next.config.js`에 `basePath: "/action"`이 설정돼 있어 Next.js가 루트(`/`)에는 페이지를 매핑하지 않는다. 실제 페이지는 `/action` 경로에만 존재한다.

**해결**: 헬스체크와 Nginx `location` 매칭을 `/`가 아니라 `/action`·`/action/` 기준으로 맞췄다(`location = /action` 정확 매치 + `location /action/` 하위 경로, 두 블록으로 커버). `curl http://localhost:3003/action`은 200으로 정상 응답하며, 루트 404는 basePath 설정상 정상 동작이라 별도 수정하지 않았다.
