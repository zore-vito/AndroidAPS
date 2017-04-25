package info.nightscout.androidaps.plugins.OpenAPSAMA;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.plugins.IobCobCalculator.IobCobCalculatorPlugin;
import info.nightscout.androidaps.data.IobTotal;
import info.nightscout.androidaps.db.BgReading;
import info.nightscout.androidaps.plugins.NSClientInternal.data.NSProfile;
import info.nightscout.utils.Round;
import info.nightscout.utils.SP;
import info.nightscout.utils.SafeParse;


public class Autosens {
    private static Logger log = LoggerFactory.getLogger(Autosens.class);

    public static AutosensResult detectSensitivityandCarbAbsorption(long dataFromTime, Long mealTime) {
        NSProfile profile = MainApp.getConfigBuilder().getActiveProfile().getProfile();

        //console.error(mealTime);

        double deviationSum = 0;
        double carbsAbsorbed = 0;

        List<BgReading> bucketed_data = IobCobCalculatorPlugin.getBucketedData(dataFromTime);
        if (bucketed_data == null)
            return new AutosensResult();

        //console.error(bucketed_data);
        double[] avgDeltas = new double[bucketed_data.size() - 2];
        double[] bgis = new double[bucketed_data.size() - 2];
        double[] deviations = new double[bucketed_data.size() - 2];

        String pastSensitivity = "";
        for (int i = 0; i < bucketed_data.size() - 3; ++i) {
            long bgTime = bucketed_data.get(i).timeIndex;
            int secondsFromMidnight = NSProfile.secondsFromMidnight(new Date(bgTime));

            String hour = "";
            //log.debug(new Date(bgTime).toString());
            if (secondsFromMidnight % 3600 < 2.5 * 60 || secondsFromMidnight % 3600 > 57.5 * 60) {
                hour += "(" + Math.round(secondsFromMidnight / 3600d) + ")";
            }

            double sens = NSProfile.toMgdl(profile.getIsf(secondsFromMidnight), profile.getUnits());

            //console.error(bgTime , bucketed_data[i].glucose);
            double bg;
            double avgDelta;
            double delta;
            bg = bucketed_data.get(i).value;
            if (bg < 39 || bucketed_data.get(i + 3).value < 39) {
                log.error("! value < 39");
                continue;
            }
            avgDelta = (bg - bucketed_data.get(i + 3).value) / 3;
            delta = (bg - bucketed_data.get(i + 1).value);

//            avgDelta = avgDelta.toFixed(2);
            IobTotal iob = IobCobCalculatorPlugin.calulateFromTreatmentsAndTemps(bgTime);

            double bgi = Math.round((-iob.activity * sens * 5) * 100) / 100d;
//            bgi = bgi.toFixed(2);
            //console.error(delta);
            double deviation = delta - bgi;
//            deviation = deviation.toFixed(2);
            //if (deviation < 0 && deviation > -2) { console.error("BG: "+bg+", avgDelta: "+avgDelta+", BGI: "+bgi+", deviation: "+deviation); }

            // Exclude large positive deviations (carb absorption) from autosens
            if (avgDelta - bgi < 6) {
                if (deviation > 0) {
                    pastSensitivity += "+";
                } else if (deviation == 0) {
                    pastSensitivity += "=";
                } else {
                    pastSensitivity += "-";
                }
                avgDeltas[i] = avgDelta;
                bgis[i] = bgi;
                deviations[i] = deviation;
                deviationSum += deviation;
            } else {
                pastSensitivity += ">";
                //console.error(bgTime);
            }
            pastSensitivity += hour;
            //log.debug("TIME: " + new Date(bgTime).toString() + " BG: " + bg + " SENS: " + sens + " DELTA: " + delta + " AVGDELTA: " + avgDelta + " IOB: " + iob.iob + " ACTIVITY: " + iob.activity + " BGI: " + bgi + " DEVIATION: " + deviation);

            // if bgTime is more recent than mealTime
            if (mealTime != null && bgTime > mealTime) {
                // figure out how many carbs that represents
                // but always assume at least 3mg/dL/5m (default) absorption
                double ci = Math.max(deviation, SP.getDouble("openapsama_min_5m_carbimpact", 3.0));
                double absorbed = ci * profile.getIc(secondsFromMidnight) / sens;
                // and add that to the running total carbsAbsorbed
                carbsAbsorbed += absorbed;
            }
        }

        double ratio = 1;
        String ratioLimit = "";
        String sensResult = "";

        if (mealTime == null) {
            //console.error("");
            log.debug(pastSensitivity);
            //console.log(JSON.stringify(avgDeltas));
            //console.log(JSON.stringify(bgis));
            Arrays.sort(avgDeltas);
            Arrays.sort(bgis);
            Arrays.sort(deviations);

            for (double i = 0.9; i > 0.1; i = i - 0.02) {
                //console.error("p="+i.toFixed(2)+": "+percentile(avgDeltas, i).toFixed(2)+", "+percentile(bgis, i).toFixed(2)+", "+percentile(deviations, i).toFixed(2));
                if (percentile(deviations, (i + 0.02)) >= 0 && percentile(deviations, i) < 0) {
                    //console.error("p="+i.toFixed(2)+": "+percentile(avgDeltas, i).toFixed(2)+", "+percentile(bgis, i).toFixed(2)+", "+percentile(deviations, i).toFixed(2));
                    log.debug(Math.round(100 * i) + "% of non-meal deviations negative (target 45%-50%)");
                }
            }
            double pSensitive = percentile(deviations, 0.50);
            double pResistant = percentile(deviations, 0.45);
            //p30 = percentile(deviations, 0.3);

//        average = deviationSum / deviations.length;

            //console.error("Mean deviation: "+average.toFixed(2));
            double basalOff = 0;

            if (pSensitive < 0) { // sensitive
                basalOff = pSensitive * (60 / 5) / NSProfile.toMgdl(profile.getIsf(NSProfile.secondsFromMidnight()), profile.getUnits());
                sensResult = "Excess insulin sensitivity detected";
            } else if (pResistant > 0) { // resistant
                basalOff = pResistant * (60 / 5) / NSProfile.toMgdl(profile.getIsf(NSProfile.secondsFromMidnight()), profile.getUnits());
                sensResult = "Excess insulin resistance detected";
            } else {
                sensResult = "Sensitivity normal";
            }
            log.debug(sensResult);
            ratio = 1 + (basalOff / profile.getMaxDailyBasal());

        // don't adjust more than 1.5x
        double rawRatio = ratio;
        ratio = Math.max(ratio, SafeParse.stringToDouble(SP.getString("openapsama_autosens_min", "0.7")));
        ratio = Math.min(ratio, SafeParse.stringToDouble(SP.getString("openapsama_autosens_max", "1.2")));

            if (ratio != rawRatio) {
                ratioLimit = "Ratio limited from " + rawRatio + " to " + ratio;
                log.debug(ratioLimit);
            }

            double newisf = Math.round(NSProfile.toMgdl(profile.getIsf(NSProfile.secondsFromMidnight()), profile.getUnits()) / ratio);
            if (ratio != 1) {
                log.debug("ISF adjusted from " + NSProfile.toMgdl(profile.getIsf(NSProfile.secondsFromMidnight()), profile.getUnits()) + " to " + newisf);
            }
            //console.error("Basal adjustment "+basalOff.toFixed(2)+"U/hr");
            //console.error("Ratio: "+ratio*100+"%: new ISF: "+newisf.toFixed(1)+"mg/dL/U");
        }

        AutosensResult output = new AutosensResult();
        output.ratio = Round.roundTo(ratio, 0.01);
        output.carbsAbsorbed = Round.roundTo(carbsAbsorbed, 0.01);
        output.pastSensitivity = pastSensitivity;
        output.ratioLimit = ratioLimit;
        output.sensResult = sensResult;
        return output;
    }

    // From https://gist.github.com/IceCreamYou/6ffa1b18c4c8f6aeaad2
    // Returns the value at a given percentile in a sorted numeric array.
    // "Linear interpolation between closest ranks" method
    public static double percentile(double[] arr, double p) {
        if (arr.length == 0) return 0;
        if (p <= 0) return arr[0];
        if (p >= 1) return arr[arr.length - 1];

        double index = arr.length * p,
                lower = Math.floor(index),
                upper = lower + 1,
                weight = index % 1;

        if (upper >= arr.length) return arr[(int) lower];
        return arr[(int) lower] * (1 - weight) + arr[(int) upper] * weight;
    }

    // Returns the percentile of the given value in a sorted numeric array.
    public static double percentRank(double[] arr, double v) {
        for (int i = 0, l = arr.length; i < l; i++) {
            if (v <= arr[i]) {
                while (i < l && v == arr[i]) i++;
                if (i == 0) return 0;
                if (v != arr[i - 1]) {
                    i += (v - arr[i - 1]) / (arr[i] - arr[i - 1]);
                }
                return i / l;
            }
        }
        return 1;
    }

}
