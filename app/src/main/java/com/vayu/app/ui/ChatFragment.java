package com.vayu.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vayu.app.MainActivity;
import com.vayu.app.R;
import com.vayu.app.ai.GeminiService;

import java.util.ArrayList;
import java.util.List;

public class ChatFragment extends Fragment {

    private RecyclerView recyclerChat;
    private ChatAdapter adapter;
    private List<ChatMessage> messages = new ArrayList<>();
    private EditText etInput;
    private ImageButton btnSend;

    private static final String TYPING_TEXT = "Thinking...";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_chat, container, false);

        recyclerChat = v.findViewById(R.id.recycler_chat);
        etInput = v.findViewById(R.id.et_message);
        btnSend = v.findViewById(R.id.btn_send);

        adapter = new ChatAdapter(messages);
        recyclerChat.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerChat.setAdapter(adapter);

        // Welcome msg
        if (messages.isEmpty()) {
            addMessage(new ChatMessage("Hello! I'm Vayu AI 🌬️\nAsk me anything about air quality, health tips, or your sensor readings!", false));
        }

        btnSend.setOnClickListener(view -> {
            String q = etInput.getText().toString().trim();
            if (q.isEmpty()) return;

            etInput.setText("");
            addMessage(new ChatMessage(q, true));

            // Show typing indicator
            final ChatMessage typingMsg = new ChatMessage(TYPING_TEXT, false);
            addMessage(typingMsg);
            btnSend.setEnabled(false);

            MainActivity activity = (MainActivity) getActivity();
            if (activity != null) {
                activity.getGeminiService().sendChat(q, new GeminiService.OutputListener() {
                    @Override
                    public void onResponse(String result) {
                        removeTypingIndicator();
                        btnSend.setEnabled(true);
                        addMessageWithAnimation(new ChatMessage(result, false));
                    }

                    @Override
                    public void onError(String error) {
                        removeTypingIndicator();
                        btnSend.setEnabled(true);
                        addMessageWithAnimation(new ChatMessage("⚠️ " + error, false));
                    }
                });
            }
        });

        return v;
    }

    private void addMessage(ChatMessage msg) {
        messages.add(msg);
        adapter.notifyItemInserted(messages.size() - 1);
        recyclerChat.smoothScrollToPosition(messages.size() - 1);
    }

    private void addMessageWithAnimation(ChatMessage msg) {
        messages.add(msg);
        int pos = messages.size() - 1;
        adapter.notifyItemInserted(pos);
        recyclerChat.smoothScrollToPosition(pos);

        // Fade in the new item
        recyclerChat.postDelayed(() -> {
            RecyclerView.ViewHolder holder = recyclerChat.findViewHolderForAdapterPosition(pos);
            if (holder != null) {
                AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
                fadeIn.setDuration(400);
                holder.itemView.startAnimation(fadeIn);
            }
        }, 100);
    }

    private void removeTypingIndicator() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (!messages.get(i).isUser && TYPING_TEXT.equals(messages.get(i).text)) {
                messages.remove(i);
                adapter.notifyItemRemoved(i);
                break;
            }
        }
    }
}
