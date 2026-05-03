import { Suspense } from "react";
import { LoginHero } from "@/components/login/LoginHero";

export default function Page() {
  return (
    <Suspense>
      <LoginHero />
    </Suspense>
  );
}
