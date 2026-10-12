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

/**
 * Fresh users get an auto-opened onboarding modal. UI-registered users
 * complete it (that is the product flow); API-onboarded users never see it.
 */
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
  await expect(page.getByTestId("quote-total")).toContainText("USD");
  await page.getByTestId("create-trade").click();

  // Contract detail: buyer funds; vault locks exactly 1,000.00 EUR.
  await expect(page).toHaveURL(/\/contracts\//);
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
  await expect(page.getByTestId("status-badge")).toHaveText("SETTLED");

  // Audit trail has the release legs.
  await page.getByTestId("tab-audit").click();
  await expect(page.getByText("Seller payout")).toBeVisible();

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
  await expect(page).toHaveURL(/\/contracts\//);

  await page.getByTestId("fund").click();
  await page.getByTestId("confirm-fund").click();
  await expect(page.getByTestId("status-badge")).toHaveText("FUNDED");
  await page.getByTestId("cancel").click();
  await page.getByTestId("confirm-cancel").click();
  await expect(page.getByTestId("status-badge")).toHaveText("CANCELLED");
});
