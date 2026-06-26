import Providers from "./providers";
import "./style.css";

export const metadata = { title: "달달해 관리자" };

export default function RootLayout({ children }) {
  return (
    <html lang="ko">
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
