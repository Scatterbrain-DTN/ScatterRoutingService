package com.example.uscatterbrain;

import android.content.Intent;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnitRunner;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeoutException;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DatastoreInstrumentedTest {

    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    @Test
    public void testBindService() throws TimeoutException {
        Intent bindIntent = new Intent(ApplicationProvider.getApplicationContext(), ScatterRoutingService.class);
        IBinder binder = serviceRule.bindService(bindIntent);
        ScatterRoutingService service = ((ScatterRoutingService.ScatterBinder)binder).getService();
    }
}
