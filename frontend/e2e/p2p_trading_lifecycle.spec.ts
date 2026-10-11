import { expect, test } from "@playwright/test";
import { createHmac } from "node:crypto";
import type { Page } from "@playwright/test";

const API = "http://localhost:8619";
const GH_SECRET = "e2e-gh-secret";
const BUYER = "buyer@demo.com";
const SELLER = "seller@demo.com";
const PASSWORD = "EscrowDemo123!";
const TRACKING_URL = "https://github.com/escrowrail/repo/pull/42";

async function loginViaUi(page: Page, email: string) {
  await page.goto("/login");
  await page.getByTestId("email").fill(email);
  await page.getByTestId("password").fill(PASSWORD);
  await page.getByTestId("submit").click();
  await expect(page).toHaveURL(/\/dashboard/);
}

async function fireGithubWebhook(
  request: {
    post: (
      url: string,
      opts: object,
    ) => Promise<{ ok: () => boolean; status: () => number }>;
  },
  trackingKey: string,
) {
  const body = JSON.stringify({
    action: "closed",
    pull_request: { merged: true, html_url: trackingKey },
  });
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

test("Complete P2P Trade: Buyer creates and funds -> Seller receives settlement", async ({
  page,
  request,
  browser,
}) => {
  // ---------- Session A: buyer creates the trade ----------
  await loginViaUi(page, BUYER);
  await expect(page.getByTestId("balance-usd")).toHaveText(/50,000\.00 USD/);

  await page.getByTestId("new-trade").click();
  await page.getByTestId("cp-email").fill(SELLER);
  await expect(page.getByTestId("cp-resolved")).toContainText(
    "Berlin Robotics GmbH",
  );
  await page.getByTestId("amount").fill("10000.00");
  await page.getByTestId("tracking-type").selectOption("GITHUB_PR");
  await page.getByTestId("tracking-key").fill(TRACKING_URL);
  await expect(page.getByTestId("quote-total")).toContainText("USD");
  await page.getByTestId("create-trade").click();

  // Trade lands in the table as INITIATED.
  await page.goto("/dashboard");
  const row = page.locator('[data-testid^="trade-row-"]', {
    hasText: "Berlin Robotics GmbH",
  });
  await expect(row).toContainText("INITIATED");
  await expect(row).toContainText("buyer");

  // Open the execution drawer and fund.
  await row.click();
  await expect(page.getByTestId("trade-drawer")).toBeVisible();
  await expect(page.getByTestId("status-badge")).toHaveText("INITIATED");
  await page.getByTestId("fund").click();
  await expect(page.getByTestId("status-badge")).toHaveText("FUNDED");
  await expect(page.getByTestId("yield-ticker")).toBeVisible();
  await page.getByTestId("drawer-close").click();

  // USD balance decremented by the deposit (~10,854 USD incl. 0.5% FX fee).
  await expect(page.getByTestId("metric-cash-usd")).not.toHaveText(
    /50,000\.00 USD/,
  );
  await expect(page.getByTestId("metric-vault")).toHaveText(/10,000\.0/);

  // ---------- Session B: seller observes + webhook settles ----------
  const ctx2 = await browser.newContext({ baseURL: "http://localhost:5173" });
  const page2 = await ctx2.newPage();
  await loginViaUi(page2, SELLER);

  const sellerRow = page2.locator('[data-testid^="trade-row-"]', {
    hasText: "Acme Industrial US",
  });
  await expect(sellerRow).toContainText("FUNDED");
  await expect(sellerRow).toContainText("seller");

  // Seller drawer is read-only: safeguarded badge, no fund button.
  await sellerRow.click();
  await expect(page2.getByTestId("status-badge")).toHaveText("FUNDED");
  await expect(
    page2.getByText("Funds Safeguarded in Vault"),
  ).toBeVisible();
  await expect(page2.getByTestId("fund")).toHaveCount(0);
  await page2.getByTestId("drawer-close").click();

  // Signed GitHub PR-merge webhook settles the contract.
  await fireGithubWebhook(request, TRACKING_URL);
  await page2.reload();
  await expect(sellerRow).toContainText("SETTLED");
  // Seller's EUR balance grew by the net payout (10,000 - 0.8% = 9,920).
  await expect(page2.getByTestId("balance-eur")).toHaveText(/19,920\.0/);
  await ctx2.close();

  // ---------- Session A: buyer sees settlement + audit trail ----------
  await page.reload();
  await expect(row).toContainText("SETTLED");
  await row.click();
  await expect(page.getByTestId("audit-body")).toContainText("Seller payout");
  await expect(page.getByText("Audit Integrity Verified")).toBeVisible();
});
