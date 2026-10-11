package com.hippo.ehviewer.reader;

import static org.junit.Assert.assertEquals;

import com.hippo.ehviewer.dao.DaoMaster;

import org.junit.Test;

public class ReadingQueueSchemaContractTest {
    @Test
    public void schemaVersionAndTableNameAreStable() {
        assertEquals(14, DaoMaster.SCHEMA_VERSION);
        assertEquals("READING_QUEUE", ReadingQueueSchema.TABLE);
        assertEquals("CURRENT_PAGE", ReadingQueueSchema.COLUMN_CURRENT_PAGE);
        assertEquals("TOTAL_PAGES", ReadingQueueSchema.COLUMN_TOTAL_PAGES);
    }
}
