import type { Metadata } from "next";
import { Fraunces, JetBrains_Mono, Manrope } from "next/font/google";
import "./globals.css";
import Sidebar from "@/components/Sidebar";

const display = Fraunces({
  variable: "--font-display",
  subsets: ["latin"],
  weight: ["500", "600"],
  style: ["normal", "italic"],
});

const body = Manrope({
  variable: "--font-body",
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
});

const mono = JetBrains_Mono({
  variable: "--font-mono",
  subsets: ["latin"],
  weight: ["400", "500", "600"],
});

export const metadata: Metadata = {
  title: "ChomuGirI",
  description: "ChomuGirI — smart AI router + coder/auditor swarm, cloud terminal, artifacts and one-click deploy.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${display.variable} ${body.variable} ${mono.variable} h-full antialiased`}
    >
      <body className="flex h-full min-h-full">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">{children}</div>
      </body>
    </html>
  );
}
