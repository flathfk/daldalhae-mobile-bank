package com.example.mobilebank.config;

import com.example.mobilebank.domain.*;
import com.example.mobilebank.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;

@Configuration
public class DataInitializer {
    @Bean
    CommandLineRunner init(UserRepository users, AccountRepository accounts, TransactionRecordRepository transactions, PasswordEncoder encoder) {
        return args -> {
            User admin = users.findByUsername("admin").orElseGet(() -> users.save(new User("admin", encoder.encode("admin123"), "관리자", UserRole.ADMIN)));
            User u1 = users.findByUsername("user1").orElseGet(() -> users.save(new User("user1", encoder.encode("1234"), "김민준", UserRole.USER)));
            User u2 = users.findByUsername("user2").orElseGet(() -> users.save(new User("user2", encoder.encode("1234"), "이서연", UserRole.USER)));
            User u3 = users.findByUsername("user3").orElseGet(() -> users.save(new User("user3", encoder.encode("1234"), "박도윤", UserRole.USER)));
            seedAccount(accounts, admin, "999-000-000001", "10000000");
            Account a1 = seedAccount(accounts, u1, "110-100-000001", "50000");
            Account a2 = seedAccount(accounts, u2, "110-100-000002", "35000");
            Account a3 = seedAccount(accounts, u3, "110-100-000003", "80000");

            if (transactions.count() == 0) {
                seedTx(transactions, u1, TransactionType.DEPOSIT, "20000", null, a1.getAccountNumber(), "충전");
                seedTx(transactions, u1, TransactionType.WITHDRAW, "4500", a1.getAccountNumber(), null, "아메리카노");
                seedTx(transactions, u2, TransactionType.DEPOSIT, "30000", null, a2.getAccountNumber(), "충전");
                seedTx(transactions, u2, TransactionType.WITHDRAW, "5500", a2.getAccountNumber(), null, "카페라떼");
                seedTx(transactions, u3, TransactionType.WITHDRAW, "5000", a3.getAccountNumber(), null, "바닐라라떼");
                seedTx(transactions, u3, TransactionType.TRANSFER_OUT, "4500", a3.getAccountNumber(), a1.getAccountNumber(), "기프티콘 선물");
                seedTx(transactions, u1, TransactionType.TRANSFER_IN, "4500", a3.getAccountNumber(), a1.getAccountNumber(), "기프티콘 선물");
            }
        };
    }

    private Account seedAccount(AccountRepository accounts, User user, String number, String amount) {
        return accounts.findByAccountNumber(number).orElseGet(() -> accounts.save(new Account(user, number, new BigDecimal(amount))));
    }

    private void seedTx(TransactionRecordRepository transactions, User user, TransactionType type, String amount, String from, String to, String memo) {
        transactions.save(new TransactionRecord(user, type, new BigDecimal(amount), from, to, memo));
    }
}
