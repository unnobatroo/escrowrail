import { expect, test } from "@playwright/test";
import type { Page, Browser } from "@playwright/test";

const API = "http://localhost:8619";
const PASSWORD = "demo-password-1";

async function registerViaApi(request: {
  post: (url: string, opts: object) => Promise<{ ok: () => boolean }>;
}, email: string, name: string, ubo: string) {
  const res = await request.post(`${API}/api/v1/auth/register`, {
    data: {
      email,
      password: PASSWORD,
      legal_name: name,
      ubo_tax_id: ubo,
      country: "DE",
    },
  });
  if (!res.ok()) throw new Error(`register ${email} failed`);
}

async function registerViaUi(
  page: Page,
  email: string,
  name: string,
  ubo: string,
) {
  await page.goto("/register");
  await page.getByTestId("legal_name").fill(name);
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(PASSWORD);
  await page.getByTestId("ubo_tax_id").fill(ubo);
  await page.getByTestId("country").fill("US");
  await page.getByTestId("submit").click();
  await expect(page).toHaveURL(/\/dashboard/);
}

async function loginViaUi(page: Page, email: string) {
  await page.goto("/login");
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(PASSWORD);
  await page.getByTestId("submit").click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test("register and login flow", async ({ page }) => {
  const email = `reg-${Date.now()}@test.dev`;
  await registerViaUi(page, email, "Registration Co", `UB-R-${Date.now()}`);
  // Nav shell shows org + sandbox balances.
  await expect(page.getByText("Registration Co")).toBeVisible();
  await expect(page.getByTestId("balance-usd")).toHaveText(/50,000\.00 USD/);

  await page.getByTestId("logout").click();
  await expect(page).toHaveURL(/\/login/);

  await loginViaUi(page, email);
  await expect(page.getByText("Registration Co")).toBeVisible();
});

test("buyer invites seller, funds in USD, webhook settles, seller paid EUR", async ({
  page,
  request,
  browser,
}) => {
  const run = Date.now();
  const sellerEmail = `seller-${run}@test.dev`;
  const buyerEmail = `buyer-${run}@test.dev`;
  await registerViaApi(request, sellerEmail, "Seller Org", `UB-S-${run}`);
  await registerViaUi(page, buyerEmail, "Buyer Org", `UB-B-${run}`);

  // Create trade via modal: counterparty resolves, live quote shows.
  await page.getByTestId("new-trade").click();
  await page.getByTestId("cp-email").fill(sellerEmail);
  await expect(page.getByTestId("cp-resolved")).toContainText("Seller Org");
  await page.getByTestId("amount").fill("1000.00");
  await page.getByTestId("tracking-type").selectOption("GITHUB_PR");
  await page.getByTestId("tracking-key").fill(`https://github.com/o/r/pull/${run}`);
  await expect(page.getByTestId("quote-total")).toContainText("USD");
  await page.getByTestId("create-trade").click();

  // Contract detail: buyer funds; vault locks exactly 1,000.00 EUR.
  await expect(page).toHaveURL(/\/contracts\//);
  await page.getByTestId("fund").click();
  await expect(page.getByTestId("status-badge")).toHaveText("FUNDED");
  await expect(page.getByTestId("vault-balance")).toHaveText("1,000.00 EUR");

  // Simulated milestone webhook settles: 0.8% fee, 992.00 EUR to seller.
  await page.getByTestId("simulate").click();
  await expect(page.getByTestId("status-badge")).toHaveText("SETTLED");

  // Audit trail has the release legs.
  await page.getByTestId("tab-audit").click();
  await expect(page.getByText("Escrow release to seller")).toBeVisible();

  // Seller session: dashboard shows the EUR payout in the nav pill.
  const ctx2 = await browser.newContext({ baseURL: "http://localhost:5173" });
  const page2 = await ctx2.newPage();
  await loginViaUi(page2, sellerEmail);
  await expect(page2.getByTestId("balance-eur")).toHaveText(/10,992\.00 EUR/);
  await expect(page2.getByTestId("trades-body")).toContainText("SETTLED");
  await ctx2.close();
});

test("ubo collision blocks submission with AML banner", async ({ page, request }) => {
  const run = Date.now();
  const sameUbo = `UB-SHARED-${run}`;
  const twinEmail = `twin-${run}@test.dev`;
  await registerViaApi(request, twinEmail, "Twin Org", sameUbo);
  await registerViaUi(page, `self-${run}@test.dev`, "Main Org", sameUbo);

  await page.getByTestId("new-trade").click();
  await page.getByTestId("cp-email").fill(twinEmail);
  await expect(page.getByTestId("ubo-warning")).toContainText(
    "Circular ownership detected",
  );
  await expect(page.getByTestId("create-trade")).toBeDisabled();
});

test("buyer cancels funded escrow and receives refund", async ({ page, request }) => {
  const run = Date.now();
  await registerViaApi(request, `s-${run}@test.dev`, "S Org", `UB-S-${run}`);
  await registerViaUi(page, `b-${run}@test.dev`, "B Org", `UB-B-${run}`);

  await page.getByTestId("new-trade").click();
  await page.getByTestId("cp-email").fill(`s-${run}@test.dev`);
  await page.getByTestId("amount").fill("1000.00");
  await page.getByTestId("tracking-key").fill(`cancel-${run}`);
  await page.getByTestId("create-trade").click();
  await expect(page).toHaveURL(/\/contracts\//);

  await page.getByTestId("fund").click();
  await expect(page.getByTestId("status-badge")).toHaveText("FUNDED");
  await page.getByTestId("cancel").click();
  await page.getByTestId("confirm-cancel").click();
  await expect(page.getByTestId("status-badge")).toHaveText("CANCELLED");
});
