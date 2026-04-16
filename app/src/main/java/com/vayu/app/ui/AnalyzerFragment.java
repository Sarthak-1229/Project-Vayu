package com.vayu.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.vayu.app.MainActivity;
import com.vayu.app.R;
import com.vayu.app.ai.GeminiService;
import com.vayu.app.firebase.FirebaseLogger;

public class AnalyzerFragment extends Fragment {

    private Button btnAnalyze;
    private ProgressBar progressBar;
    private TextView tvResult;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_analyzer, container, false);

        btnAnalyze = v.findViewById(R.id.btn_analyze);
        progressBar = v.findViewById(R.id.progress_analyzer);
        tvResult = v.findViewById(R.id.tv_analysis_result);

        btnAnalyze.setOnClickListener(view -> conductAnalysis());

        return v;
    }

    private void conductAnalysis() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        btnAnalyze.setEnabled(false);
        btnAnalyze.setAlpha(0.6f);
        progressBar.setVisibility(View.VISIBLE);
        tvResult.setText("Fetching recent AQI trends from Firebase...");

        activity.getFirebaseLogger().fetchRecentData(new FirebaseLogger.FetchCallback() {
            @Override
            public void onSuccess(String jsonArrayData) {
                if (jsonArrayData == null || jsonArrayData.equals("null")) {
                    showError("No data found in Firebase for analysis.");
                    return;
                }

                tvResult.setText("Analyzing data with Gemini AI...");

                activity.getGeminiService().analyzeTrends(jsonArrayData, new GeminiService.OutputListener() {
                    @Override
                    public void onResponse(String result) {
                        progressBar.setVisibility(View.GONE);
                        btnAnalyze.setEnabled(true);
                        btnAnalyze.setAlpha(1f);

                        // Fade in result
                        tvResult.setAlpha(0f);
                        tvResult.setText(result);
                        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
                        fadeIn.setDuration(500);
                        fadeIn.setFillAfter(true);
                        tvResult.startAnimation(fadeIn);
                        tvResult.setAlpha(1f);
                    }

                    @Override
                    public void onError(String error) {
                        showError("AI Analysis failed: " + error);
                    }
                });
            }

            @Override
            public void onError(String error) {
                showError("Firebase Fetch failed: " + error);
            }
        });
    }

    private void showError(String msg) {
        progressBar.setVisibility(View.GONE);
        btnAnalyze.setEnabled(true);
        btnAnalyze.setAlpha(1f);
        tvResult.setText(msg);
        Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
    }
}
