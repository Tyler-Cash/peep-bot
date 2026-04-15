package dev.tylercash.event.contract;

import com.fasterxml.jackson.databind.JsonNode;
import dev.tylercash.event.contract.model.ContractOutcome;
import dev.tylercash.event.contract.model.ContractTrade;
import java.awt.BasicStroke;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.encoders.EncoderUtil;
import org.jfree.chart.encoders.ImageFormat;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@AllArgsConstructor
public class ContractGraphService {

    private final LmsrService lmsr;

    private static final Color BACKGROUND = new Color(0x1E, 0x1F, 0x22);
    private static final Color PLOT_BACKGROUND = new Color(0x2B, 0x2D, 0x31);
    private static final Color GRIDLINE = new Color(0x3D, 0x3F, 0x44);
    private static final Color TEXT = new Color(0xDC, 0xDD, 0xDE);

    private static final Color[] SERIES_COLORS = {
        new Color(0x57, 0xF2, 0x87), // green (YES)
        new Color(0xED, 0x42, 0x45), // red (NO)
        new Color(0x5B, 0x65, 0xF2), // blue
        new Color(0xF0, 0xA7, 0x32), // orange
        new Color(0xE0, 0x91, 0xFF), // purple
    };

    public byte[] renderChart(
            List<ContractOutcome> outcomes, List<ContractTrade> trades, Instant createdAt, double b) {
        TimeSeriesCollection dataset = new TimeSeriesCollection();
        TimeSeries[] series = new TimeSeries[outcomes.size()];
        for (int i = 0; i < outcomes.size(); i++) {
            series[i] = new TimeSeries(outcomes.get(i).getLabel());
            dataset.addSeries(series[i]);
        }

        // Synthetic first point at equal probability
        double initialProb = 100.0 / outcomes.size();
        for (TimeSeries s : series) {
            s.addOrUpdate(new Millisecond(Date.from(createdAt)), initialProb);
        }

        // Each trade's effect is shown immediately at its timestamp using prob_after.
        // prob_after for trade[i] = prob_before of trade[i+1] (already stored).
        // For the final trade, calculate from current outcome shares.
        for (int t = 0; t < trades.size(); t++) {
            ContractTrade trade = trades.get(t);
            JsonNode probAfter;
            if (t + 1 < trades.size()) {
                probAfter = trades.get(t + 1).getProbBefore();
            } else {
                probAfter = null; // will use calculated values below
            }

            Instant timestamp = trade.getTradedAt();
            for (int i = 0; i < outcomes.size(); i++) {
                ContractOutcome outcome = outcomes.get(i);
                double value;
                if (probAfter != null) {
                    JsonNode node = probAfter.get(outcome.getId().toString());
                    value = node != null ? node.asDouble() * 100.0 : initialProb;
                } else {
                    double[] q = outcomes.stream()
                            .mapToDouble(ContractOutcome::getSharesOutstanding)
                            .toArray();
                    value = lmsr.probability(q, i, b) * 100.0;
                }
                series[i].addOrUpdate(new Millisecond(Date.from(timestamp)), value);
            }
        }

        JFreeChart chart =
                ChartFactory.createTimeSeriesChart(null, null, "Probability (%)", dataset, true, false, false);
        styleChart(chart, outcomes.size());

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            EncoderUtil.writeBufferedImage(chart.createBufferedImage(600, 200), ImageFormat.PNG, out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Failed to render contract graph", e);
            throw new RuntimeException("Chart rendering failed", e);
        }
    }

    private void styleChart(JFreeChart chart, int numSeries) {
        chart.setBackgroundPaint(BACKGROUND);
        chart.getLegend().setBackgroundPaint(BACKGROUND);
        chart.getLegend().setItemPaint(TEXT);

        XYPlot plot = chart.getXYPlot();
        plot.setBackgroundPaint(PLOT_BACKGROUND);
        plot.setDomainGridlinePaint(GRIDLINE);
        plot.setRangeGridlinePaint(GRIDLINE);
        plot.setOutlineVisible(false);

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setLabelPaint(TEXT);
        domainAxis.setTickLabelPaint(TEXT);

        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();
        rangeAxis.setRange(0, 100);
        rangeAxis.setLabelPaint(TEXT);
        rangeAxis.setTickLabelPaint(TEXT);

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        for (int i = 0; i < numSeries; i++) {
            Color c = SERIES_COLORS[i % SERIES_COLORS.length];
            renderer.setSeriesPaint(i, c);
            renderer.setSeriesStroke(i, new BasicStroke(2.5f));
        }
        plot.setRenderer(renderer);
    }
}
