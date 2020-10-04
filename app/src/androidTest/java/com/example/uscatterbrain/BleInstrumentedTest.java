package com.example.uscatterbrain;

import android.content.Intent;
import android.os.IBinder;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.ServiceTestRule;

import org.junit.Rule;
import org.junit.Test;


import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.concurrent.TimeoutException;

public class BleInstrumentedTest {

    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    public ScatterRoutingServiceImpl getService() throws TimeoutException {
        Intent bindIntent = new Intent(ApplicationProvider.getApplicationContext(), ScatterRoutingServiceImpl.class);
        IBinder binder = serviceRule.bindService(bindIntent);
        return ((ScatterRoutingServiceImpl.ScatterBinder)binder).getService();
    }

    @Test
    public void gattServerTest() throws TimeoutException {
        ScatterRoutingServiceImpl service = getService();
        assertThat(service != null, is(true));

    }
}