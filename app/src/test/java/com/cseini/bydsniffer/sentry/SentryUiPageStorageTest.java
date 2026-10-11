package com.cseini.bydsniffer.sentry;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;

public class SentryUiPageStorageTest {
    @Test
    public void storagePage_hasFormatFlowAndKeepsGrantQuery() throws Exception {
        String h = SentryUiPage.buildStorage("&sgx=a&sg=b");
        assertTrue(h.contains("/sentry/format"));
        assertTrue(h.contains("/drive/settings"));
        assertTrue(h.contains("/drive/status"));
        assertTrue(h.contains("confirm:'format'"));
        assertTrue(h.contains("tab=storage&sgx=a&sg=b"));
        Files.write(Paths.get("build/storage-page.html"), h.getBytes("UTF-8"));
    }
}
