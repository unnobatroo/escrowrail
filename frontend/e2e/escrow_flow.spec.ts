import { expect, test, type Page } from "@playwright/test";

async function selectByLabel(page: Page, testId: string, label: string) {
  await page.getByTestId(testId).selectOption({ label });
}

test("test_ubo_collision_blocks_submission", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("BACKEND HEALTHY")).toBeVisible();

  // Acme Hardware US and Acme Subsidiary DE share UBO TAX-US-1001.
  await selectByLabel(page, "buyer-select", "Acme Hardware US (UBO: TAX-US-1001)");
  await selectByLabel(page, "seller-select", "Acme Subsidiary DE (UBO: TAX-US-1001)");

  const banner = page.getByTestId("ubo-error");
  await expect(banner).toBeVisible();
  await expect(banner).toContainText("Circular ownership detected");
  await expect(banner).toContainText("TAX-US-1001");
  await expect(page.getByTestId("create-fund-btn")).toBeDisabled();
});

test("test_happy_path_settlement", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("BACKEND HEALTHY")).toBeVisible();

  await selectByLabel(page, "buyer-select", "Acme Hardware US (UBO: TAX-US-1001)");
  await selectByLabel(page, "seller-select", "Berlin Robotics GmbH (UBO: TAX-DE-2002)");
  await page.getByTestId("amount-input").fill("10000");
  await selectByLabel(page, "milestone-select", "GitHub PR — unnobatroo/wise-hack#1");

  // real-time spot quote: principal 10,800 + fee on ~10,854
  await expect(page.getByTestId("gross-deposit")).toContainText("10,854");

  await page.getByTestId("create-fund-btn").click();
  await expect(page.getByText("FUNDED — FUNDS SAFEGUARDED")).toBeVisible({
    timeout: 15000,
  });
  await expect(page.getByTestId("vault-balance")).toContainText("10,000");

  // live float-yield ticker accrues
  const ticker = page.getByTestId("yield-counter");
  await expect(ticker).toBeVisible();
  const first = await ticker.textContent();
  await page.waitForTimeout(600);
  const second = await ticker.textContent();
  expect(Number(second!.replace("€", ""))).toBeGreaterThan(
    Number(first!.replace("€", "")),
  );

  // simulated GitHub merge webhook settles the contract
  await page.getByTestId("webhook-github-btn").click();
  await expect(page.getByText("SETTLED").first()).toBeVisible({ timeout: 15000 });

  const breakdown = page.getByTestId("settlement-breakdown");
  await expect(breakdown).toBeVisible();
  await expect(breakdown).toContainText("-80.00 EUR"); // 0.8% fee
  await expect(page.getByTestId("seller-net")).toContainText("9,920.00 EUR");

  // audit console recorded the webhook round-trip
  await expect(page.getByTestId("console-log")).toContainText("/webhooks/github");
});

test("test_cancellation_flow", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("BACKEND HEALTHY")).toBeVisible();

  await selectByLabel(page, "buyer-select", "Acme Hardware US (UBO: TAX-US-1001)");
  await selectByLabel(page, "seller-select", "Berlin Robotics GmbH (UBO: TAX-DE-2002)");
  await page.getByTestId("amount-input").fill("500");
  await selectByLabel(page, "milestone-select", "DHL Delivery — DHL-987654");

  await page.getByTestId("create-fund-btn").click();
  await expect(page.getByText("FUNDED — FUNDS SAFEGUARDED")).toBeVisible({
    timeout: 15000,
  });

  await page.getByTestId("cancel-btn").click();
  await expect(page.getByText("Cancel escrow contract?")).toBeVisible();
  await page.getByTestId("confirm-cancel-btn").click();

  await expect(page.getByText("CANCELLED").first()).toBeVisible({ timeout: 15000 });
  await expect(page.getByTestId("cancelled-note")).toBeVisible();
});
