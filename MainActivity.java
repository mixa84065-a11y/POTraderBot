package com.potraderbot;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private final TradeEngine engine = new TradeEngine();
    private TextView status, stats, log;
    private boolean running = false;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(36, 40, 36, 36);
        page.setBackgroundColor(0xFF101827);
        scroll.addView(page);

        TextView title = text("PO TRADER BOT", 26, 0xFFFFFFFF, true);
        page.addView(title);
        page.addView(text("Android prototype • simulation only", 14, 0xFF9FB0C5, false));

        status = text("STATUS: STOPPED", 20, 0xFFFFC857, true);
        status.setPadding(0, 36, 0, 12);
        page.addView(status);

        TextView config = text("EUR/USD • 1-minute candles • 3-minute expiry\nEMA 9/21 + RSI 14 • max 10 simulated trades/day\nPause after 2 consecutive simulated losses", 15, 0xFFE5EDF7, false);
        config.setPadding(0, 12, 0, 24);
        page.addView(config);

        Button start = button("START ANALYSIS (SAMPLE DATA)");
        start.setOnClickListener(v -> runSampleAnalysis());
        page.addView(start);

        Button stop = button("STOP BOT");
        stop.setOnClickListener(v -> {
            running = false;
            status.setText("STATUS: STOPPED");
            status.setTextColor(0xFFFFC857);
        });
        page.addView(stop);

        stats = text("Trades: 0 / 10", 17, 0xFFFFFFFF, true);
        stats.setPadding(0, 28, 0, 10);
        page.addView(stats);

        log = text("No trades recorded.\nSample candles are synthetic and do not represent live market prices.", 14, 0xFFB8C6D9, false);
        page.addView(log);
        page.addView(text("\nIMPORTANT: This prototype does not connect to Pocket Option, read its app screen, or place trades. Do not enter account credentials.", 13, 0xFFFFC857, true));
        setContentView(scroll);
    }

    private void runSampleAnalysis() {
        running = true;
        List<Candle> candles = makeSyntheticCandles();
        MarketAnalyzer.Signal signal = new MarketAnalyzer().analyze(candles);
        status.setText("SIMULATION SIGNAL: " + signal);
        status.setTextColor(signal == MarketAnalyzer.Signal.WAIT ? 0xFFFFC857 : 0xFF70D6A5);

        if (signal != MarketAnalyzer.Signal.WAIT && !engine.isPaused()) {
            Candle last = candles.get(candles.size() - 1);
            // Deliberately do not fabricate a trade outcome or auto-place an order.
            log.setText("Signal generated from synthetic sample candles: " + signal
                    + "\nSample close: " + last.close
                    + "\nNo trade was placed. Add a verified live/demo data feed before performance testing.");
        } else {
            log.setText("No entry. The strategy requires confirmation or the risk guard is active."
                    + "\nNo trade was placed.");
        }
        stats.setText("Simulated trades recorded: " + engine.getTradesToday() + " / 10");
    }

    private List<Candle> makeSyntheticCandles() {
        List<Candle> list = new ArrayList<>();
        double p = 1.0800;
        long now = System.currentTimeMillis();
        for (int i = 0; i < 40; i++) {
            double open = p;
            double delta = Math.sin(i * 0.7) * 0.00012 + (i % 7 == 0 ? -0.00004 : 0.000025);
            double close = open + delta;
            double high = Math.max(open, close) + 0.00005;
            double low = Math.min(open, close) - 0.00005;
            list.add(new Candle(now - (40 - i) * 60_000L, open, high, low, close));
            p = close;
        }
        return list;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }
}
