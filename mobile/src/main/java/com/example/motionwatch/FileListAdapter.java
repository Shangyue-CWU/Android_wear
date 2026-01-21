package com.example.motionwatch;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.Date;

public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.FileViewHolder> {

    private List<File> files;

    public FileListAdapter(List<File> files) {
        this.files = files;
    }

    public static class FileViewHolder extends RecyclerView.ViewHolder {
        TextView filename;
        TextView info;

        public FileViewHolder(View view) {
            super(view);
            filename = view.findViewById(R.id.txt_filename);
            info = view.findViewById(R.id.txt_info);
        }
    }

    @Override
    public FileViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.file_item, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(FileViewHolder holder, int position) {
        File file = files.get(position);

        holder.filename.setText(file.getName());
        holder.info.setText(
                "Size: " + (file.length() / 1024) + " KB\n"
                        + "Modified: " + new Date(file.lastModified()).toString()
        );
    }

    @Override
    public int getItemCount() {
        return files.size();
    }
}
