import { test, expect, Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { randomBytes } from "node:crypto";
const env = Object.fromEntries(
  readFileSync("../.env", "utf8")
    .trim()
    .split("\n")
    .map((line) => {
      const n = line.indexOf("=");
      return [line.slice(0, n), line.slice(n + 1)];
    }),
);
const base = "http://localhost:8080";
let password = "";
async function keycloak(path: string, method = "GET", body?: unknown) {
  const token = await fetch(
    base + "/auth/realms/company/protocol/openid-connect/token",
    {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "client_credentials",
        client_id: "employee-provisioner",
        client_secret: env.PROVISIONER_SECRET,
      }),
    },
  ).then((r) => r.json());
  const res = await fetch(base + "/auth/admin/realms/company" + path, {
    method,
    headers: {
      Authorization: "Bearer " + token.access_token,
      "Content-Type": "application/json",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw Error("Fixture provisioning failed: " + res.status);
  return res.status === 204 || res.status === 201 ? null : res.json();
}
async function login(page: Page, username = "browser-admin") {
  await page.goto("/admin/");
  await page.getByLabel("Username or email").fill(username);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign In", exact: true }).click();
}
test.beforeAll(async () => {
  password = randomBytes(24).toString("hex");
  const users = await keycloak("/users?username=browser-admin&exact=true");
  if (users.length) await keycloak("/users/" + users[0].id, "DELETE");
  await keycloak("/users", "POST", {
    username: "browser-admin",
    firstName: "Browser",
    lastName: "Test",
    email: "browser-admin@example.test",
    enabled: true,
    emailVerified: true,
    requiredActions: [],
    credentials: [{ type: "password", value: password, temporary: false }],
  });
  const [user] = await keycloak("/users?username=browser-admin&exact=true");
  const roles = await Promise.all(
    ["company-admin", "employee"].map((role) => keycloak("/roles/" + role)),
  );
  await keycloak("/users/" + user.id + "/role-mappings/realm", "POST", roles);
});
test("administrator creates collection, uploads and chats with a cited answer", async ({
  page,
}) => {
  await login(page);
  await expect(
    page.getByRole("main", { name: "Documents", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Collections", exact: true }).click();
  await page.getByRole("button", { name: "New collection" }).click();
  const name = "Handbook " + Date.now();
  const fileName = "travel-policy-" + Date.now() + ".txt";
  await page.getByLabel("Collection name").fill(name);
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Documents", exact: true }).click();
  await page.getByRole("combobox").click();
  await page.getByRole("option", { name, exact: true }).click();
  await page.locator("input[type=file]").setInputFiles({
    name: fileName,
    mimeType: "text/plain",
    buffer: Buffer.from(
      "Company policy: employees can claim approved rail travel expenses within 30 days.",
    ),
  });
  await expect(page.getByText(fileName, { exact: true })).toBeVisible();
  await expect(
    page
      .locator(".table-row")
      .filter({ hasText: fileName })
      .getByText("Ready", { exact: true }),
  ).toBeVisible({ timeout: 30000 });
  await page.evaluate(() => document.fonts.ready);
  expect(
    await page
      .locator(".brand mat-icon")
      .evaluate((el) => getComputedStyle(el).fontFamily),
  ).toContain("Material Icons");
  expect(
    await page.evaluate(() => document.fonts.check('24px "Material Icons"')),
  ).toBe(true);
  await page.screenshot({
    path: "../.runtime/admin-desktop.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 390);
  await page.screenshot({
    path: "../.runtime/admin-mobile.png",
    fullPage: true,
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/chat/");
  await page.waitForTimeout(1500);
  await page.screenshot({ path: "../.runtime/chat.png", fullPage: true });
  await expect(
    page.getByText("company-knowledge", { exact: true }).first(),
  ).toBeVisible({ timeout: 15000 });
  const composer = page.locator("[contenteditable=true]").last();
  await composer.fill("What is the rail travel policy?");
  await composer.press("Enter");
  await expect(
    page.locator('[aria-label="Assistant message with actions"]').first(),
  ).toContainText("Mock response", { timeout: 15000 });
  await expect(
    page.locator('a[href^="/api/documents/"]').first(),
  ).toBeVisible();
  await page.screenshot({
    path: "../.runtime/chat-answer.png",
    fullPage: true,
  });
  // Also exercise the authenticated streaming adapter directly and inspect citations.
  const answer = await page.evaluate(async () => {
    const token =
      document.cookie
        .split("; ")
        .find((x) => x.startsWith("XSRF-TOKEN="))
        ?.slice(11) || "";
    const res = await fetch("/v1/chat/completions", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": decodeURIComponent(token),
      },
      body: JSON.stringify({
        stream: true,
        messages: [
          { role: "user", content: "What is the rail travel policy?" },
        ],
      }),
    });
    return { status: res.status, text: await res.text() };
  });
  expect(answer.status).toBe(200);
  expect(answer.text).toContain("/api/documents/");
  expect(answer.text).toContain("[DONE]");
});
test("mobile collection layout remains usable and unauthenticated APIs reject access", async ({
  page,
  request,
}) => {
  expect((await request.get("/api/me", { maxRedirects: 0 })).status()).toBe(
    401,
  );
  await login(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "Collections", exact: true }).click();
  await expect(
    page.getByRole("main", { name: "Collections", exact: true }),
  ).toBeVisible();
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth),
  ).toBeLessThanOrEqual(390);
});
