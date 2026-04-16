package com.vayu.app.ui;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.widget.Button;
import android.widget.LinearLayout;
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
import com.vayu.app.model.AqiAnalysis;

public class AnalyzerFragment extends Fragment {

    private Button btnAnalyze;
    private ProgressBar progressBar;
    private TextView tvResult;
    private LinearLayout reportSection;
    private TextView tvCategoryBadge, tvTrend, tvReadingCount;
    private TextView tvStatMin, tvStatAvg, tvStatMax;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_analyzer, container, false);

        btnAnalyze = v.findViewById(R.id.btn_analyze);
        progressBar = v.findViewById(R.id.progress_analyzer);
        tvResult = v.findViewById(R.id.tv_analysis_result);
        reportSection = v.findViewById(R.id.report_section);
        tvCategoryBadge = v.findViewById(R.id.tv_category_badge);
        tvTrend = v.findViewById(R.id.tv_trend);
        tvReadingCount = v.findViewById(R.id.tv_reading_count);
        tvStatMin = v.findViewById(R.id.tv_stat_min);
        tvStatAvg = v.findViewById(R.id.tv_stat_avg);
        tvStatMax = v.findViewById(R.id.tv_stat_max);

        btnAnalyze.setOnClickListener(view -> conductAnalysis());

        return v;
    }

    private void conductAnalysis() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        btnAnalyze.setEnabled(false);
        btnAnalyze.setAlpha(0.6f);
        progressBar.setVisibility(View.VISIBLE);
        reportSection.setVisibility(View.GONE);
        tvResult.setText("Fetching recent AQI data from Firebase...");

        activity.getFirebaseLogger().fetchRecentData(new FirebaseLogger.FetchCallback() {
            @Override
            public void onSuccess(String jsonData) {
                if (jsonData == null || jsonData.equals("null")) {
                    showError("No data found in Firebase for analysis.");
                    return;
                }

                // ── Step 1: Clean and analyze data locally ──
                AqiAnalysis analysis;
                try {
                    analysis = AqiAnalysis.fromFirebaseJson(jsonData);
                } catch (Exception e) {
                    showError("Failed to parse Firebase data: " + e.getMessage());
                    return;
                }

                if (analysis == null || analysis.readingCount == 0) {
                    showError("No valid readings found after cleaning data.");
                    return;
                }

                // ── Step 2: Populate visual report ──
                populateReport(analysis);

                // ── Step 3: Send structured summary to AI ──
                tvResult.setText("Generating health insights with AI...");

                String prompt = analysis.toPromptSummary() +
                    "\nBased on this data, provide:\n" +
                    "1. A health assessment for the user\n" +
                    "2. Specific recommendations for respiratory health\n" +
                    "3. Any precautions they should take based on the trend\n" +
                    "Keep it concise, actionable, and in plain text (no markdown).";

                activity.getGeminiService().analyzeTrends(prompt, new GeminiService.OutputListener() {
                    @Override
                    public void onResponse(String result) {
                        progressBar.setVisibility(View.GONE);
                        btnAnalyze.setEnabled(true);
                        btnAnalyze.setAlpha(1f);

                        // Store analysis for chatbot context
                        analysis.aiInsight = result;
                        activity.setLatestAnalysis(analysis);

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

    private void populateReport(AqiAnalysis a) {
        reportSection.setVisibility(View.VISIBLE);

        // Fade in the report section
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(400);
        reportSection.startAnimation(fadeIn);

        // Stats
        tvStatMin.setText(String.valueOf(a.min));
        tvStatAvg.setText(String.format("%.0f", a.avg));
        tvStatMax.setText(String.valueOf(a.max));

        // Category badge - set text and dynamically tint background color
        tvCategoryBadge.setText(a.category);
        GradientDrawable badgeBg = (GradientDrawable) tvCategoryBadge.getBackground().mutate();
        badgeBg.setColor(a.categoryColor);

        // Trend
        tvTrend.setText(a.trendArrow + " " + a.trend);
        if ("Rising".equals(a.trend)) {
            tvTrend.setTextColor(0xFFEF4444); // red
        } else if ("Falling".equals(a.trend)) {
            tvTrend.setTextColor(0xFF22C55E); // green
        } else {
            tvTrend.setTextColor(0xFF64748B); // gray
        }

        // Reading count
        tvReadingCount.setText(a.readingCount + " readings");
    }

    private void showError(String msg) {
        progressBar.setVisibility(View.GONE);
        btnAnalyze.setEnabled(true);
        btnAnalyze.setAlpha(1f);
        tvResult.setText(msg);
        Toast.makeText(getContext(), msg, Toast.LENGTH_LONG).show();
    }
}
