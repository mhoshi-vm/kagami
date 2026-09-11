package am.ik.kagami;

import am.ik.kagami.mockserver.MockServer;
import am.ik.kagami.mockserver.MockServer.Response;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Browser-driven end-to-end test of the web UI, from login to token generation. The
 * storage backend is supplied by the subclass so that every backend runs the same
 * scenario.
 * <p>
 * The UI is built into {@code target/classes/META-INF/resources} by the
 * {@code frontend-maven-plugin} in the {@code compile} phase and Chromium is installed by
 * the {@code exec-maven-plugin} in the {@code process-test-classes} phase, so a plain
 * {@code ./mvnw test} is enough. When running from an IDE, run
 * {@code ./mvnw process-test-classes} once beforehand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "spring.security.user.name=test", "spring.security.user.password={noop}pass" })
@Import(MockConfig.class)
public abstract class BrowserE2ETestBase {

	static final String POM_PATH = "am/ik/kagami/kagami/0.0.1/kagami-0.0.1.pom";

	static final String POM_CONTENT = "<project></project>";

	static Playwright playwright;

	static Browser browser;

	@LocalServerPort
	int port;

	@Autowired
	MockServer mockServer;

	@Autowired
	RestClient.Builder restClientBuilder;

	BrowserContext context;

	Page page;

	@BeforeAll
	static void launchBrowser() {
		// Only Chromium is installed by the build; do not download Firefox and WebKit
		playwright = Playwright
			.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
		browser = playwright.chromium().launch();
	}

	@AfterAll
	static void closeBrowser() {
		if (browser != null) {
			browser.close();
		}
		if (playwright != null) {
			playwright.close();
		}
	}

	@BeforeEach
	void openPage() {
		this.context = browser.newContext();
		this.page = this.context.newPage();
		this.page.setDefaultTimeout(15_000);
	}

	@AfterEach
	void closePage() {
		this.context.close();
	}

	@Test
	void browseRepositoryAndGenerateToken() {
		String baseUrl = "http://localhost:" + this.port;
		String sha1 = sha1(POM_CONTENT);
		this.mockServer.GET("/" + POM_PATH, req -> Response.ok(POM_CONTENT))
			.GET("/" + POM_PATH + ".sha1", req -> Response.ok(sha1));

		// Mirror the artifact and its checksum through the artifact endpoint so that the
		// storage backend under test is populated by the real fetch path
		RestClient restClient = this.restClientBuilder.baseUrl(baseUrl).build();
		restClient.get().uri("/artifacts/mock/" + POM_PATH).retrieve().toBodilessEntity();
		restClient.get().uri("/artifacts/mock/" + POM_PATH + ".sha1").retrieve().toBodilessEntity();

		// Unauthenticated access is redirected to the login page
		this.page.navigate(baseUrl + "/");
		assertThat(this.page).hasURL(Pattern.compile("/login$"));
		this.page.fill("#username", "test");
		this.page.fill("#password", "pass");
		this.page.locator("form button[type=submit]").click();
		assertThat(this.page).hasURL(baseUrl + "/");

		// Repository list shows the mock repository with the mirrored artifact
		Locator repositoryRow = this.page.locator("tbody tr", new Page.LocatorOptions().setHasText("mock"));
		assertThat(repositoryRow).isVisible();
		assertThat(repositoryRow).containsText("1");
		repositoryRow.click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock");

		// Drill into a directory and back via the breadcrumb
		this.page.getByText("am", new Page.GetByTextOptions().setExact(true)).click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock/am");
		assertThat(this.page.getByText("ik", new Page.GetByTextOptions().setExact(true))).isVisible();
		this.page.locator("nav button", new Page.LocatorOptions().setHasText("mock")).click();
		assertThat(this.page).hasURL(baseUrl + "/browse/mock");
		assertThat(this.page.getByText("am", new Page.GetByTextOptions().setExact(true))).isVisible();

		// Open the file info modal and check size and checksum
		this.page.navigate(baseUrl + "/browse/mock/am/ik/kagami/kagami/0.0.1");
		// Exact match so that the ".pom.sha1" sibling row is not picked up as well
		Locator fileRow = this.page.locator("div.group", new Page.LocatorOptions()
			.setHas(this.page.getByText("kagami-0.0.1.pom", new Page.GetByTextOptions().setExact(true))));
		fileRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Info")).click();
		assertThat(this.page.getByText("File Information / kagami-0.0.1.pom")).isVisible();
		assertThat(this.page.getByText(POM_CONTENT.length() + " B")).isVisible();
		assertThat(this.page.getByText(sha1)).isVisible();
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Close")).click();
		assertThat(this.page.getByText("File Information / kagami-0.0.1.pom")).not().isVisible();

		// Generate a token for the mock repository
		this.page.navigate(baseUrl + "/token");
		this.page.locator("label", new Page.LocatorOptions().setHasText("mock"))
			.locator("input[type=checkbox]")
			.check();
		this.page.locator("label", new Page.LocatorOptions().setHasText("Read Artifacts"))
			.locator("input[type=checkbox]")
			.check();
		this.page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Generate Token")).click();
		assertThat(this.page.getByText("Token Generated")).isVisible();
		assertThat(this.page.locator("div.select-all"))
			.containsText(Pattern.compile("^eyJ[\\w-]+\\.[\\w-]+\\.[\\w-]+$"));
	}

	static String sha1(String content) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
