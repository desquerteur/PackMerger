package sh.pcx.packmerger.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The update check reads this fork's GitHub releases and never offers pre-releases or dev builds. */
class UpdateCheckerParseTest {

    private static String release(String tag, boolean prerelease, boolean draft) {
        return "{\"tag_name\":\"" + tag + "\",\"name\":\"PackMerger " + tag + "\",\"prerelease\":" + prerelease
                + ",\"draft\":" + draft + ",\"html_url\":\"https://github.com/desquerteur/PackMerger/releases/tag/"
                + tag + "\"}";
    }

    @Test
    void githubRelease_newerVersionIsAnUpdate() {
        UpdateChecker.Result r = UpdateChecker.parse(release("v1.5.0", false, false), "1.4.0");
        assertNotNull(r);
        assertEquals("1.5.0", r.latest());
        assertTrue(r.updateAvailable());
        assertEquals("https://github.com/desquerteur/PackMerger/releases/tag/v1.5.0", r.url());
    }

    @Test
    void githubRelease_sameVersionIsUpToDate() {
        UpdateChecker.Result r = UpdateChecker.parse(release("v1.4.0", false, false), "1.4.0");
        assertNotNull(r);
        assertFalse(r.updateAvailable());
    }

    @Test
    void preReleasesDraftsAndDevBuilds_areIgnored() {
        assertNull(UpdateChecker.parse(release("v9.0.0", true, false), "1.4.0"));
        assertNull(UpdateChecker.parse(release("v9.0.0", false, true), "1.4.0"));
        assertNull(UpdateChecker.parse(release("dev-42-abc1234", false, false), "1.4.0"));
    }

    @Test
    void manifestFormat_stillWorks() {
        UpdateChecker.Result r = UpdateChecker.parse(
                "{\"latest\":\"1.4.1\",\"latest_url\":\"https://example.com\",\"latest_notes\":\"fixes\"}", "1.4.0");
        assertNotNull(r);
        assertTrue(r.updateAvailable());
        assertEquals("fixes", r.notes());
    }

    @Test
    void garbage_isIgnored() {
        assertNull(UpdateChecker.parse("not json", "1.4.0"));
        assertNull(UpdateChecker.parse("[]", "1.4.0"));
        assertNull(UpdateChecker.parse("{\"message\":\"Not Found\"}", "1.4.0"));
    }

    @Test
    void userAgent_namesTheFork() {
        assertTrue(UpdateChecker.userAgent("1.4.0").startsWith("desquerteur/PackMerger/1.4.0"));
    }
}
