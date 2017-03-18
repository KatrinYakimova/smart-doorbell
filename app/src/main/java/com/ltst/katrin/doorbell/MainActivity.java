package com.ltst.katrin.doorbell;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import com.google.android.things.contrib.driver.button.Button;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
