package com.flow.adhd;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Minimal APK context smoke test; this does not validate app behavior. */
@RunWith(AndroidJUnit4.class)
public class FlowAppContextSmokeTest {
    @Test public void targetApplicationContextUsesFlowPackage() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(BuildConfig.APPLICATION_ID, appContext.getPackageName());
    }
}
