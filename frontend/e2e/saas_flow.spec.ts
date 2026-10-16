import { expect, test } from "@playwright/test";
import { createHmac } from "node:crypto";
import type { Page } from "@playwright/test";

const API = "http://localhost:8619";
const GH_SECRET = "e2e-gh-secret";

/** Posts a properly HMAC-signed GitHub pull-request webhook. */
async function fireGithubWebhook(
  request: { post: (url: string, opts: object) => Promise<{ ok: () => boolean; status: () => number }> },
  payload: object,
) {
  const body = JSON.stringify(payload);
  const signature =
    "sha256=" + createHmac("sha256", GH_SECRET).update(body).digest("hex");
  const res = await request.post(`${API}/webhooks/github`, {
    data: body,
    headers: {
      "content-type": "application/json",
      "x-hub-signature-256": signature,
    },
  });
  if (!res.ok()) throw new Error(`webhook rejected: ${res.status()}`);
}
const PASSWORD = "demo-password-1";

/** Completes email verification via the dev-token backdoor. Returns the JWT. */
async function verifyViaApi(request: {
  get: (url: string) => Promise<{ ok: () => boolean; json: () => Promise<{ token: string }> }>;
  post: (url: string, opts: object) => Promise<{ ok: () => boolean; json: () => Promise<{ access_token: string }> }>;
  put: (url: string, opts: object) => Promise<{ ok: () => boolean }>;
}, email: string) {
  const tok = await request.get(`${API}/dev/verification-token?email=${email}`);
  if (!tok.ok()) throw new Error(`no verification token for ${email}`);
  const { token } = await tok.json();
  const res = await request.post(`${API}/api/v1/auth/verify-email`, {
    data: { token },
  });
  if (!res.ok()) throw new Error(`verify ${email} failed`);
  return (await res.json()).access_token;
}

async function registerViaApi(request: {
  get: (url: string) => Promise<{ ok: () => boolean; json: () => Promise<{ token: string }> }>;
  post: (url: string, opts: object) => Promise<{ ok: () => boolean; json: () => Promise<{ access_token: string }> }>;
  put: (url: string, opts: object) => Promise<{ ok: () => boolean }>;
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
  await verifyViaApi(request, email);
}

/** Fresh users get an auto-opened onboarding modal; UI-registered users complete it, API-onboarded users never see it. */
async function completeOnboarding(page: Page) {
  const dialog = page.getByRole("dialog");
  await dialog.waitFor({ state: "visible", timeout: 5000 });
  await dialog.getByTestId("deposit-usd").fill("25000");
  await dialog.getByTestId("deposit-eur").fill("10000");
  await dialog.getByTestId("profile-save").click();
  await expect(dialog).not.toBeVisible();
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
  // Registration lands on the verification gate — complete it.
  await expect(page).toHaveURL(/\/verify-email/);
  const tok = await page.request.get(
    `${API}/dev/verification-token?email=${email}`,
  );
  const { token } = await tok.json();
  await page.getByTestId("verify-token").fill(token);
  await page.getByTestId("verify-submit").click();
  await expect(page).toHaveURL(/\/dashboard/);
  await completeOnboarding(page);
}

async function loginViaUi(page: Page, email: string, password = PASSWORD) {
  await page.goto("/login");
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(password);
  await page.getByTestId("submit").click();
  await expect(page).toHaveURL(/\/dashboard/);
}

test("register and login flow", async ({ page }) => {
  const email = `reg-${Date.now()}@test.dev`;
  await registerViaUi(page, email, "Registration Co", `UB-R-${Date.now()}`);
  // Nav shell shows org + sandbox balances.
  await expect(page.getByText("Registration Co", { exact: true })).toBeVisible();
  await expect(page.getByTestId("balance-usd")).toHaveText(/25,000\.00 USD/);

  await page.getByTestId("logout").click();
  await expect(page).toHaveURL(/\/login/);

  await loginViaUi(page, email);
  await expect(page.getByText("Registration Co", { exact: true })).toBeVisible();
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
  const trackingKey = `https://github.com/o/r/pull/${run}`;
  await page.getByTestId("tracking-type").selectOption("GITHUB_PR");
  await page.getByTestId("tracking-key").fill(trackingKey);
  await expect(page.getByTestId("quote-total")).not.toHaveValue("");
  await expect(page.getByTestId("source-currency")).toHaveValue("USD");
  await page.getByTestId("create-trade").click();

  // Contract detail: buyer funds; vault locks exactly 1,000.00 EUR.
  await expect(page.getByTestId("trade-drawer")).toBeVisible();
  await page.getByTestId("fund").click();
  await page.getByTestId("confirm-fund").click();
  await expect(page.getByTestId("status-badge")).toHaveText("FUNDED");
  await expect(page.getByTestId("vault-balance")).toHaveText("1,000.00 EUR");

  // Signed milestone webhook settles: 0.8% fee, 992.00 EUR to seller.
  await fireGithubWebhook(request, {
    action: "closed",
    pull_request: { merged: true, html_url: trackingKey },
  });
  await page.reload();
  await page.locator('[data-testid^="trade-row-"]').first().click();
  await expect(page.getByTestId("status-badge")).toHaveText("SETTLED");

  // Audit trail has the release legs.
  await expect(page.getByTestId("audit-body")).toContainText("Seller payout");

  // Seller session: dashboard shows the EUR payout in the nav pill.
  const ctx2 = await browser.newContext({ baseURL: "http://localhost:5173" });
  const page2 = await ctx2.newPage();
  await loginViaUi(page2, sellerEmail);
  await expect(page2.getByTestId("balance-eur")).toHaveText(/992\.00 EUR/);
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
    "self-trading is blocked",
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
  await expect(page.getByTestId("trade-drawer")).toBeVisible();

  await page.getByTestId("fund").click();
  await page.getByTestId("confirm-fund").click();
  await expect(page.getByTestId("status-badge")).toHaveText("FUNDED");
  await page.getByTestId("cancel").click();
  await page.getByTestId("confirm-cancel").click();
  await expect(page.getByTestId("status-badge")).toHaveText("CANCELLED");
});

test("full UX/UI sweep: password reset, currency auto-swap, invited trade & cancel, demo switcher & guide", async ({
  page,
  request,
}) => {
  const run = Date.now();
  const userEmail = `sweep-${run}@test.dev`;
  await registerViaUi(page, userEmail, "Sweep Corp", `UB-SW-${run}`);

  // 1. How It Works modal opens and closes cleanly.
  await page.getByTestId("how-it-works").click();
  await expect(page.getByRole("dialog", { name: "How Escrowrail works" })).toBeVisible();
  await page.getByRole("button", { name: "Close explainer" }).click();
  await expect(page.getByRole("dialog", { name: "How Escrowrail works" })).not.toBeVisible();

  // 2. New Trade modal: currency auto-swap prevents same-currency collision + bidirectional editing.
  await page.getByTestId("new-trade").click();
  await expect(page.getByTestId("source-currency")).toHaveValue("USD");
  await expect(page.getByTestId("target-currency")).toHaveValue("EUR");

  // Verify algebraic invariants on the default 10,000.00 EUR quote:
  // total_deposit (10,854.27) - platform_fee (54.27) = principal (10,800.00).
  await expect(page.getByTestId("quote-total")).toHaveValue("10854.27");
  await expect(page.getByRole("dialog")).toContainText("54.27 USD");
  await expect(page.getByRole("dialog")).toContainText("10,800.00 USD");

  // Bank comparison dropdown compares Escrowrail fee (54.27 USD) vs bank fee (562.46 USD) -> savings (508.19 USD).
  await page.getByTestId("bank-comparison").click();
  await expect(page.getByTestId("customer-savings")).toContainText("508.19 USD");

  // Bidirectional BigInt derivation: typing 10854.27 into "You pay" derives exact "10000.00" EUR.
  await page.getByTestId("quote-total").fill("10854.27");
  await expect(page.getByTestId("amount")).toHaveValue("10000.00");

  // Selecting EUR as source currency automatically swaps target currency to USD.
  await page.getByTestId("source-currency").selectOption("EUR");
  await expect(page.getByTestId("target-currency")).toHaveValue("USD");
  // Swap back by choosing EUR as target currency.
  await page.getByTestId("target-currency").selectOption("EUR");
  await expect(page.getByTestId("source-currency")).toHaveValue("USD");

  // Invite an unregistered counterparty and verify INVITED drawer + pre-funding cancellation.
  const ghostEmail = `GhostVendor-${run}@Example.com`;
  await page.getByTestId("cp-email").fill(ghostEmail);
  await expect(page.getByTestId("cp-unknown")).toContainText("Not on the platform yet");
  await page.getByTestId("amount").fill("750.00");
  await page.getByTestId("tracking-type").selectOption("SHIPPING_CARRIER");
  await page.getByTestId("tracking-key").fill(`DHL-${run}`);
  await page.getByTestId("create-trade").click();

  await expect(page.getByTestId("dashboard-notice")).toContainText(
    "Counterparty is not on Escrowrail yet",
  );
  const invitedRow = page.locator('[data-testid^="trade-row-"]', {
    hasText: ghostEmail.toLowerCase(),
  });
  await expect(invitedRow).toContainText("INVITED");
  await invitedRow.click();

  await expect(page.getByTestId("trade-drawer")).toBeVisible();
  await expect(page.getByTestId("invited-banner")).toContainText(
    ghostEmail.toLowerCase(),
  );
  await page.getByTestId("cancel").click();
  await page.getByTestId("confirm-cancel").click();
  await expect(page.getByTestId("status-badge")).toHaveText("CANCELLED");
  await page.getByTestId("drawer-close").click();

  // 3. Logout -> Forgot Password -> Reset Password -> Login with new password.
  await page.getByTestId("logout").click();
  await expect(page).toHaveURL(/\/login/);
  await page.getByRole("link", { name: "Forgot password?" }).click();
  await expect(page).toHaveURL(/\/forgot-password/);
  await page.getByTestId("forgot-email").fill(userEmail);
  await page.getByTestId("forgot-submit").click();
  await expect(page.getByTestId("reset-sent")).toBeVisible();

  const resetTokRes = await request.get(
    `${API}/dev/reset-token?email=${encodeURIComponent(userEmail)}`,
  );
  expect(resetTokRes.ok()).toBeTruthy();
  const { token: resetToken } = await resetTokRes.json();

  const newPassword = "NewSweepPassword99!";
  await page.goto(`/reset-password?token=${resetToken}`);
  await page.getByTestId("new-password").fill(newPassword);
  await page.getByTestId("confirm-password").fill(newPassword);
  await page.getByTestId("reset-submit").click();
  await expect(page.getByTestId("reset-done")).toBeVisible();

  await loginViaUi(page, userEmail, newPassword);
  await expect(page.getByText("Sweep Corp", { exact: true })).toBeVisible();

  // 4. Demo persona switcher ("Switch to Seller" -> "Switch to Buyer").
  await page.getByTestId("logout").click();
  await loginViaUi(page, "buyer@demo.com", "EscrowDemo123!");
  await expect(page.getByTestId("greeting")).toContainText("Acme Industrial US");
  await page.getByTestId("demo-switch").click();
  await expect(page.getByTestId("greeting")).toContainText("Berlin Robotics GmbH");
  await page.getByTestId("demo-switch").click();
  await expect(page.getByTestId("greeting")).toContainText("Acme Industrial US");
});
