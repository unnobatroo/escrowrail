import { expect, test } from "@playwright/test";

const API = "http://localhost:8619";
const PASSWORD = "demo-password-1";

test("registration requires email verification before dashboard access", async ({
  page,
  request,
}) => {
  const email = `newuser_${Date.now()}@company.com`;

  // Register — lands on the verification gate, NOT the dashboard.
  await page.goto("/register");
  await page.getByTestId("legal_name").fill("Verification Co");
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(PASSWORD);
  await page.getByTestId("ubo_tax_id").fill(`UB-V-${Date.now()}`);
  await page.getByTestId("country").fill("US");
  await page.getByTestId("submit").click();
  await expect(page).toHaveURL(/\/verify-email\?email=/);
  await expect(page.getByTestId("verify-email")).toContainText(email);

  // Unverified login is blocked with the resend banner.
  await page.goto("/login");
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(PASSWORD);
  await page.getByTestId("submit").click();
  await expect(page.getByTestId("unverified-banner")).toContainText(
    "not verified yet",
  );
  await page.getByRole("link", { name: "resend verification email" }).click();
  await expect(page).toHaveURL(/\/verify-email/);

  // Extract the issued token via the dev backdoor and submit it on the UI.
  const tok = await request.get(
    `${API}/dev/verification-token?email=${email}`,
  );
  expect(tok.ok()).toBeTruthy();
  const { token } = await tok.json();
  await page.getByTestId("verify-token").fill(token);
  await page.getByTestId("verify-submit").click();

  // Verified: straight into the dashboard with sandbox balance pills.
  await expect(page).toHaveURL(/\/dashboard/);
  await expect(page.getByTestId("balance-usd")).toHaveText(/50,000\.00 USD/);
  await expect(page.getByTestId("balance-eur")).toHaveText(/10,000\.00 EUR/);
});
