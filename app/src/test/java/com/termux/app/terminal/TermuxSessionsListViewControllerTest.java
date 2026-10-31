package com.termux.app.terminal;

import android.app.Application;
import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class TermuxSessionsListViewControllerTest {
    @Test public void sourceChangesCannotInvalidateListViewBeforeNotification() {
        // Attach a context without starting Termux or launching native shell processes.
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        List<TermuxSession> source = new ArrayList<>();
        source.add(null);
        TermuxSessionsListViewController adapter = new TermuxSessionsListViewController(activity, source) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                // Row rendering is unrelated to the adapter-count contract under test.
                return recycled == null ? new TextView(getContext()) : recycled;
            }
        };
        ListView list = new ListView(activity);
        list.setAdapter(adapter);
        layout(list);

        int[] notifications = {0};
        adapter.registerDataSetObserver(new DataSetObserver() {
            @Override public void onChanged() {
                notifications[0]++;
                assertEquals(source.size(), adapter.getCount());
            }
        });

        // Simulate a service addition while the activity is disconnected.
        source.add(null);
        assertEquals(1, adapter.getCount());
        layout(list); // A live shared list throws ListView's count-mismatch exception here.
        adapter.notifyDataSetChanged();
        assertEquals(2, adapter.getCount());
        assertEquals(1, notifications[0]);
        layout(list);

        source.clear();
        assertEquals(2, adapter.getCount());
        layout(list);
        adapter.notifyDataSetChanged();
        assertEquals(0, adapter.getCount());
        assertEquals(2, notifications[0]);
        layout(list);

        source.add(null);
        adapter.notifyDataSetChanged();
        assertEquals(1, adapter.getCount());
        assertEquals(3, notifications[0]);
        layout(list);
    }

    private void layout(ListView list) {
        list.forceLayout();
        list.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 400, 600);
    }
}
