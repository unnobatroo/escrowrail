import { expect, test, type Page } from "@playwright/test";

const API = "http://localhost:8619";
const PASSWORD = "demo-password-1";

/** Completes email verification via the dev-token backdoor and lands authed. */
async function verifyAndEnter(page: Page, email: string) {
  await expect(page).toHaveURL(/\/verify-email/);
  const tok = await page.request.get(
    `${API}/dev/verification-token?email=${email}`,
  );
  const { token } = await tok.json();
  await page.getByTestId("verify-token").fill(token);
  await page.getByTestId("verify-submit").click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test("randomize identity → onboarding → shortfall → top-up → fund", async ({
  page,
}) => {

  const email = `persona-${Date.now()}@company.com`;

  // ---------- 1. Register with one-click random identity ----------
  await page.goto("/register");
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(PASSWORD);
  await page.getByTestId("randomize").click();
  await expect(page.getByTestId("legal_name")).not.toHaveValue("");
  await expect(page.getByTestId("ubo_tax_id")).toHaveValue(
    /^[A-Z]{2}-[A-Z]{2,5}-\d{3,}$/,
  );
  await expect(page.getByTestId("country")).toHaveValue(/^[A-Z]{2}$/);
  await page.getByTestId("submit").click();
  await verifyAndEnter(page, email);

  // ---------- 2. Onboarding modal auto-opens: generate + save ----------
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.getByTestId("randomize").click();
  await expect(dialog.getByTestId("profile-name")).not.toHaveValue("");
  await expect(dialog.getByTestId("profile-taxid")).toHaveValue(
    /^[A-Z]{2}-[A-Z]{2,5}-\d{3,}$/,
  );
  const generatedName = await dialog
    .getByTestId("profile-name")
    .inputValue();
  await dialog.getByTestId("deposit-usd").fill("25000");
  await dialog.getByTestId("deposit-eur").fill("10000");
  await dialog.getByTestId("profile-save").click();
  await expect(dialog).not.toBeVisible();

  // Friendly Neptune greeting uses the generated entity name.
  await expect(page.getByText(`Welcome back, ${generatedName}`)).toBeVisible();

  // ---------- 3. Create a trade bigger than the onboarding balance ----------
  await page.getByTestId("new-trade").click();
  await page.getByTestId("cp-email").fill("seller@demo.com");
  await page.getByTestId("amount").fill("30000");
  await page.getByTestId("tracking-key").fill(`safety-${Date.now()}`);
  await expect(page.getByTestId("quote-total")).not.toHaveValue("");
  await expect(page.getByTestId("source-currency")).toHaveValue("USD");
  await page.getByTestId("create-trade").click();
  await expect(page.getByTestId("trade-drawer")).toBeVisible();

  // ---------- 4. Insufficient balance → friendly shortfall, no debt ----------
  await page.getByTestId("fund").click();
  await expect(page.getByTestId("shortfall-banner")).toContainText(
    "You need $",
  );
  await expect(page.getByTestId("shortfall-banner")).toContainText("more");
  await expect(page.getByTestId("confirm-fund")).toBeDisabled();
  await page.getByTestId("sheet-close").click();

  // ---------- 5. Top up through profile settings, then fund ----------
  await page.goto("/dashboard");
  await page.getByTestId("profile-settings").click();
  // Demo sandbox top-up: +5,000 USD per click. Wait for each top-up
  // response so a busy-state click can never be silently swallowed.
  for (let i = 0; i < 4; i++) {
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes("/demo/topup") && r.status() === 200,
      ),
      dialog.getByTestId("settings-topup-usd").click(),
    ]);
  }
  await page.getByTestId("settings-close").click();

  // Open the trade drawer and fund for real.
  await page
    .locator("tbody tr", { hasText: "Berlin Robotics GmbH" })
    .first()
    .click();
  await page.getByTestId("fund").click();
  await expect(page.getByTestId("confirm-fund")).toBeEnabled();
  await page.getByTestId("confirm-fund").click();
  await expect(page.getByTestId("status-badge")).toHaveText("FUNDED");
});
