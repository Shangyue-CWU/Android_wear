package com.example.motionwatch;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class WearActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear);

        RecyclerView recycler = findViewById(R.id.recycler_files);
        recycler.setLayoutManager(new LinearLayoutManager(this));

        File folder = new File(getFilesDir(), "synced_logs");
        File[] filesArray = folder.listFiles();

        List<File> files = (filesArray != null)
                ? Arrays.asList(filesArray)
                : Collections.emptyList();

        // Sort latest first
        Collections.sort(files, (f1, f2) ->
                Long.compare(f2.lastModified(), f1.lastModified()));

        recycler.setAdapter(new FileListAdapter(files));
    }
}
