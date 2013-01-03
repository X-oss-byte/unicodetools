package org.unicode.text.UCA;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.unicode.cldr.util.Counter;
import org.unicode.text.UCA.MappingsForFractionalUCA.MappingWithSortKey;
import org.unicode.text.UCA.PrimariesToFractional.PrimaryToFractional;
import org.unicode.text.UCA.UCA.CollatorType;
import org.unicode.text.UCA.UCA_Statistics.RoBitSet;
import org.unicode.text.UCD.Default;
import org.unicode.text.UCD.ToolUnicodePropertySource;
import org.unicode.text.UCD.UCD_Types;
import org.unicode.text.utility.ChainException;
import org.unicode.text.utility.DualWriter;
import org.unicode.text.utility.OldEquivalenceClass;
import org.unicode.text.utility.Pair;
import org.unicode.text.utility.Utility;

import com.ibm.icu.dev.util.CollectionUtilities;
import com.ibm.icu.text.Transform;
import com.ibm.icu.text.UTF16;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.text.UnicodeSetIterator;

public class FractionalUCA {
    private static PrintWriter fractionalLog;
    private static boolean DEBUG = false;

    private static class HighByteToReorderingToken {
        private PrimariesToFractional ps2f;
        private ReorderingTokens[] highByteToReorderingToken = new ReorderingTokens[256];
        private boolean mergedScripts = false;

        HighByteToReorderingToken(PrimariesToFractional ps2f) {
            this.ps2f = ps2f;

            for (int i = 0; i < highByteToReorderingToken.length; ++i) {
                highByteToReorderingToken[i] = new ReorderingTokens();
            }
            // set special values
            highByteToReorderingToken[0].reorderingToken.add("TERMINATOR",1);
            highByteToReorderingToken[1].reorderingToken.add("LEVEL-SEPARATOR",1);
            highByteToReorderingToken[2].reorderingToken.add("FIELD-SEPARATOR",1);
            highByteToReorderingToken[3].reorderingToken.add("SPACE",1);
            for (int i = Fractional.IMPLICIT_BASE_BYTE; i <= Fractional.IMPLICIT_MAX_BYTE; ++i) {
                highByteToReorderingToken[i].reorderingToken.add("IMPLICIT",1);
            }
            for (int i = Fractional.IMPLICIT_MAX_BYTE+1; i < Fractional.SPECIAL_BASE; ++i) {
                highByteToReorderingToken[i].reorderingToken.add("TRAILING",1);
            }
            for (int i = Fractional.SPECIAL_BASE; i <= 0xFF; ++i) {
                highByteToReorderingToken[i].reorderingToken.add("SPECIAL",1);
            }
        }

        void addScriptsIn(long fractionalPrimary, String value) {
            // don't add to 0
            int leadByte = Fractional.getLeadByte(fractionalPrimary);
            if (leadByte != 0) {
                highByteToReorderingToken[leadByte].addInfoFrom(fractionalPrimary, value);
            }
        }

        public String getInfo(boolean doScripts) {
            if (!mergedScripts) {
                throw new IllegalArgumentException();
            }
            StringBuilder builder = new StringBuilder();
            Map<String, Counter<Integer>> map = new TreeMap<String, Counter<Integer>>();
            for (int i = 0; i < highByteToReorderingToken.length; ++i) {
                addScriptCats(map, i, doScripts ? highByteToReorderingToken[i].reorderingToken : highByteToReorderingToken[i].types);
            }
            appendReorderingTokenCatLine(builder, map, doScripts);
            return builder.toString();
        }

        private void addScriptCats(Map<String, Counter<Integer>> map, int i, Counter<String> scripts) {
            for (String script : scripts) {
                long count = scripts.get(script);
                Counter<Integer> scriptCounter = map.get(script);
                if (scriptCounter == null) map.put(script, scriptCounter = new Counter<Integer>());
                scriptCounter.add(i, count);
            }
        }

        private void appendReorderingTokenCatLine(StringBuilder builder, Map<String, Counter<Integer>> map, boolean doScripts) {
            for (String item : map.keySet()) {
                builder.append("[" +
                        (doScripts ? "reorderingTokens" : "categories") +
                        "\t").append(item).append('\t');
                Counter<Integer> counter2 = map.get(item);
                boolean first = true;
                for (Integer i : counter2) {
                    if (first) first = false; else builder.append(' ');

                    builder.append(Utility.hex(i, 2));
                    if (!doScripts) {
                        String stringScripts = CollectionUtilities.join(highByteToReorderingToken[i].reorderingToken.keySet(), " ");
                        if (stringScripts.length() != 0) {
                            builder.append('{').append(stringScripts).append("}");
                        }
                    }
                    builder.append('=').append(counter2.get(i));
                }
                builder.append(" ]\n");
            }
        }

        public String toString() {
            if (!mergedScripts) {
                throw new IllegalArgumentException();
            }
            StringBuilder result = new StringBuilder();
            for (int k = 0; k < highByteToReorderingToken.length; ++k) {
                ReorderingTokens tokens = highByteToReorderingToken[k];
                result.append("[top_byte\t" + Utility.hex(k,2) + "\t");
                tokens.appendTo(result, false);
                if (ps2f.isCompressibleLeadByte(k)) {
                    result.append("\tCOMPRESS");
                }
                result.append(" ]");
                // No need to output the number of characters per top_byte,
                // only causes gratuitous diffs.
                // If anything, the number of *primary weights* per top_byte might be interesting.
                // result.append("\t#\t");
                // tokens.appendTo(result, true);
                result.append("\n");
            }
            return result.toString();
        }

        private void cleanup(Map<Integer,String> overrides) {
            mergedScripts = true;
            ReorderingTokens[] mergedTokens = new ReorderingTokens[256];

            for (int k = 0; k < highByteToReorderingToken.length; ++k) {
                mergedTokens[k] = new ReorderingTokens(highByteToReorderingToken[k]);
            }
            // Any bytes that share scripts need to be combined
            // brute force, because we don't care about speed
            boolean fixedOne = true;
            while(fixedOne) {
                fixedOne = false;
                for (int k = 1; k < mergedTokens.length; ++k) {
                    if (mergedTokens[k-1].intersects(mergedTokens[k]) 
                            && !mergedTokens[k-1].equals(mergedTokens[k])) {
                        mergedTokens[k-1].or(mergedTokens[k]);
                        mergedTokens[k] = mergedTokens[k-1];
                        fixedOne = true;
                    }
                }
            }
            for (Entry<Integer, String> override : overrides.entrySet()) {
                int value = override.getKey();
                String tag = override.getValue();
                mergedTokens[value].setScripts(tag);
            }
            for (int k = 0; k < highByteToReorderingToken.length; ++k) {
                if (mergedTokens[k].reorderingToken.isEmpty()) {
                    mergedTokens[k].setScripts("Hani");
                    mergedTokens[k].reorderingToken.add("Hans", 1);
                    mergedTokens[k].reorderingToken.add("Hant", 1);
                }
                highByteToReorderingToken[k] = mergedTokens[k];
            }
        }
    }

    /**
     * Finds the minimum or maximum fractional collation element
     * among those added via {@link MinMaxFCE#setValue(int, int, int, String)}.
     */
    private static class MinMaxFCE {
        static final long UNDEFINED_MAX = Long.MAX_VALUE;
        static final long UNDEFINED_MIN = Long.MIN_VALUE;
        long[] key;
        boolean max;
        boolean debugShow = false;
        String source;
        String title;

        MinMaxFCE (boolean max, String title) {
            this.max = max;
            this.title = title;
            if (max) {
                key = new long[] {UNDEFINED_MIN, UNDEFINED_MIN, UNDEFINED_MIN};    // make small!
            } else {
                key = new long[] {UNDEFINED_MAX, UNDEFINED_MAX, UNDEFINED_MAX};
            }
        }

        /*
        FCE (boolean max, int primary, int secondary, int tertiary) {
            this(max);
            key[0] = fixWeight(primary);
            key[1] = fixWeight(secondary);
            key[2] = fixWeight(tertiary);
        }

        FCE (boolean max, int primary) {
            this(max);
            key[0] = primary & INT_MASK;
        }
         */

        boolean isUnset() {
            return key[0] == UNDEFINED_MIN || key[0] == UNDEFINED_MAX;
        }

        /**
         * Left-justifies the weight.
         * If the weight is not zero, then the lead byte is moved to bits 31..24.
         */
        static long fixWeight(int weight) {
            long result = weight & FractionalUCA.Variables.INT_MASK;
            if (result == 0) {
                return result;
            }
            while ((result & 0xFF000000) == 0) {
                result <<= 8; // shift to top
            }
            return result;
        }

        String formatFCE() {
            return formatFCE(false);
        }

        String formatFCE(boolean showEmpty) {
            String b0 = getBuffer(key[0], false);
            boolean key0Defined = key[0] != UNDEFINED_MIN && key[0] != UNDEFINED_MAX;
            if (showEmpty && b0.length() == 0) {
                b0 = "X";
            }

            String b1 = getBuffer(key[1], key0Defined);
            boolean key1Defined = key[1] != UNDEFINED_MIN && key[1] != UNDEFINED_MAX;
            if (b1.length() != 0) {
                b1 = " " + b1;
            } else if (showEmpty) {
                b1 = " X";
            }

            String b2 = getBuffer(key[2], key0Defined || key1Defined);
            if (b2.length() != 0) {
                b2 = " " + b2;
            } else if (showEmpty) {
                b2 = " X";
            }

            return "[" + b0 + "," + b1  + "," + b2 + "]";
        }

        String getBuffer(long val, boolean haveHigher) {
            if (val == UNDEFINED_MIN) {
                return "?";
            } 
            if (val == UNDEFINED_MAX) {
                if (haveHigher) {
                    val = Fractional.COMMON_SEC << 24;
                } else {
                    return "?";
                }
            }
            return Fractional.hexBytes(val);
        }

        long getValue(int zeroBasedLevel) {
            return key[zeroBasedLevel];
        }

        public String toString() {
            return toString(false);
        }

        String toString(boolean showEmpty) {
            String src = source.length() == 0 ? "CONSTRUCTED" : Default.ucd().getCodeAndName(source);
            return "[" + (max ? "last " : "first ") + title + " " + formatFCE(showEmpty) + "] # " + src;
        }

        void setValue(int npInt, int nsInt, int ntInt, String source) {
            if (debugShow) {
                System.out.println("Setting FCE: " 
                        + Utility.hex(npInt) + ", "  + Utility.hex(nsInt) + ", "  + Utility.hex(ntInt));
            }
            // to get the sign right!
            long np = fixWeight(npInt);
            long ns = fixWeight(nsInt);
            long nt = fixWeight(ntInt);
            if (max) {
                // return if the key is LEQ
                if (np < key[0]) {
                    return;
                }
                if (np == key[0]) {
                    if (ns < key[1]) {
                        return;
                    }
                    if (ns == key[1]) {
                        if (nt <= key[2]) {
                            return;
                        }
                    }
                }
            } else {
                // return if the key is GEQ
                if (np > key[0]) {
                    return;
                }
                if (np == key[0]) {
                    if (ns > key[1]) {
                        return;
                    }
                    if (ns == key[1]) {
                        if (nt >= key[2]) {
                            return;
                        }
                    }
                }
            }
            // we didn't bail, so reset!
            key[0] = np;
            key[1] = ns;
            key[2] = nt;
            this.source = source;
        }
    }

    private static class Variables {
        static final long INT_MASK = 0xFFFFFFFFL;

        static final int  BYTES_TO_AVOID     = 3, OTHER_COUNT = 256 - BYTES_TO_AVOID, LAST_COUNT = OTHER_COUNT / 2;

        static final int secondaryDoubleStart = 0xD0;
    }

    public static class FractionalStatistics {
        private PrimariesToFractional ps2f;
        private Map<Long,UnicodeSet> primaries = new TreeMap<Long,UnicodeSet>();
        private Map<Long,UnicodeSet> secondaries = new TreeMap<Long,UnicodeSet>();
        private Map<Long,UnicodeSet> tertiaries = new TreeMap<Long,UnicodeSet>();

        public FractionalStatistics(PrimariesToFractional ps2f) {
            this.ps2f = ps2f;
        }

        private void addToSet(Map<Long, UnicodeSet> map, int key0, String codepoints) {
            // shift up
            key0 = leftJustify(key0);
            Long key = (long)key0;
            UnicodeSet secondarySet = map.get(key);
            if (secondarySet == null) {
                map.put(key, secondarySet = new UnicodeSet());
            }
            secondarySet.add(codepoints);
        }
        private int leftJustify(int key0) {
            if (key0 != 0) {
                while ((key0 & 0xFF000000) == 0) {
                    key0 <<= 8;
                }
            }
            return key0;
        }
        public void show(Appendable summary) {
            /* We do not compute fractional implicit weights any more.
            checkImplicits();
            */
            try {
                show(Type.primary, primaries, summary);
                show(Type.secondary, secondaries, summary);
                show(Type.tertiary, tertiaries, summary);
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
        /* We do not compute fractional implicit weights any more.
        private void checkImplicits() {
            TreeMap<Integer, Integer> implicitToCodepoint = new TreeMap<Integer, Integer>();
            for (int codepoint = 0; codepoint <= 0x10FFFF; ++codepoint) {
                int implicitWeight = leftJustify(implicit.getImplicitFromCodePoint(codepoint));
                implicitToCodepoint.put(implicitWeight, codepoint);
            }
            int lastImplicit = 0;
            int lastCodepoint = 0;
            for (Entry<Integer, Integer> entry : implicitToCodepoint.entrySet()) {
                int implicitWeight = entry.getKey();
                int codepoint = entry.getValue();
                if (lastImplicit != 0) {
                    try {
                        checkGap(Type.primary, lastImplicit, implicitWeight);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Strings:\t" 
                                + Default.ucd().getCodeAndName(lastCodepoint) 
                                + ", " + Default.ucd().getCodeAndName(codepoint), e);
                    }
                }
                lastImplicit = implicitWeight;
                lastCodepoint = codepoint;
            }
        }
        */

        private static final UnicodeSet AVOID_CODE_POINTS =  // doesn't depend on version
                new UnicodeSet("[[:C:][:Noncharacter_Code_Point:]]").freeze();

        private static String safeString(String s, int maxLength) {
            if (AVOID_CODE_POINTS.containsSome(s)) {
                StringBuilder sb = new StringBuilder(s);
                for (int i = 0; i < sb.length();) {
                    int c = sb.codePointAt(i);
                    int next = i + Character.charCount(c);
                    if (AVOID_CODE_POINTS.contains(c)) {
                        String replacement;
                        if (c <= 0xffff) {
                            replacement = String.format("\\u%04X", c);
                        } else {
                            replacement = String.format("\\U%08X", c);
                        }
                        sb.replace(i, next, replacement);
                        next = i + replacement.length();
                    }
                    i = next;
                }
                s = sb.toString();
            }

            if (s.length() > maxLength) {
                if (Character.isHighSurrogate(s.charAt(maxLength - 1))) {
                    --maxLength;
                }
                s = s.substring(0, maxLength) + "...";
            }
            return s;
        }
        private void show(Type type, Map<Long, UnicodeSet> map, Appendable summary) throws IOException {
            summary.append("\n# Start " + type + " statistics \n");
            long lastWeight = -1;
            for (Entry<Long, UnicodeSet> entry : map.entrySet()) {
                long weight = entry.getKey();
                if (lastWeight >= 0) {
                    checkGap(type, (int)lastWeight, (int)weight);
                }
                lastWeight = weight;

                UnicodeSet uset = entry.getValue();
                String pattern = uset.toPattern(false);
                summary.append(Fractional.hexBytes(weight))
                .append('\t')
                .append(String.valueOf(uset.size()))
                .append('\t')
                .append(safeString(pattern, 100))
                .append('\n')
                ;
            }
            summary.append("# End " + type + "  statistics \n");
        }

        enum Type {
            primary(5), secondary(5), tertiary(5); // TODO figure out the right values, use constants
            final int gap;
            Type(int gap) {
                this.gap = gap;
            }
            public int gap() {
                return gap;
            }
        }

        /**
         * Check that there is enough room between weight1 and the weight2. Both
         * are left-justified: that is, of the form XXYYZZWW where XX is only
         * zero if the whole value is zero.
         * 
         * @param type
         * @param weight1
         * @param weight2
         */
        private static void checkGap(Type type, int weight1, int weight2) {
            if (weight1 >= weight2) {
                throw new IllegalArgumentException("Weights out of order: " + Fractional.hexBytes(weight1) + ",\t" + Fractional.hexBytes(weight2));
            }
            if (true) { return; }  // TODO: fix & reenable
            // find the first difference between bytes
            for (int i = 24; i >= 0; i -= 8) {
                int b1 = (int) ((weight1 >>> i) & 0xFF);
                int b2 = (int) ((weight2 >>> i) & 0xFF);
                // keep going until we find a byte difference
                if (b2 == b1) {
                    continue;
                }
                if (b2 > b1 + 27) {
                    // OK, there is a gap of 27 or greater. Example:
                    // AA BB CC 38
                    // AA BB CC 5C
                    return;
                }
                if (i != 0) { // if we are at not at the end

                    if (b2 > b1 + 1) {
                        // OK, there is a gap of 1 or greater, and we are not in the final byte. Example:
                        // AA 85
                        // AA 87
                        return;
                    }
                    // We now know that b2 == b1 +1

                    // get next bytes
                    i -= 8;
                    b1 = (int) ((weight1 >> i) & 0xFF);
                    b2 = 0x100 + (int) ((weight2 >> i) & 0xFF); // add 100 to express the true difference
                    if (b1 + type.gap() < b2) {
                        // OK, the gap is enough.
                        // AA 85
                        // AA 86 05
                        // or
                        // AA 85 FD
                        // AA 86 03
                        return;
                    }
                }
                throw new IllegalArgumentException("Weights too close: " + Fractional.hexBytes(weight1) + ",\t" + Fractional.hexBytes(weight2));
            }
            throw new IllegalArgumentException("Internal Error: " + Fractional.hexBytes(weight1) + ",\t" + Fractional.hexBytes(weight2));
        }

        private StringBuffer sb = new StringBuffer();

        private void printAndRecord(boolean show, String chr, int np, int ns, int nt, String comment) {
            try {
                addToSet(primaries, np, chr);
                addToSet(secondaries, ns, chr);
                addToSet(tertiaries, nt, chr);

                sb.setLength(0);
                if (show) {
                    sb.append(Utility.hex(chr)).append(";\t");
                }

                sb.append('[');
                Fractional.hexBytes(np, sb);
                sb.append(", ");
                Fractional.hexBytes(ns, sb);
                sb.append(", ");
                Fractional.hexBytes(nt, sb);
                sb.append(']');

                if (comment != null) {
                    sb.append('\t').append(comment).append('\n');
                }

                fractionalLog.print(sb);
            } catch (Exception e) {
                throw new ChainException("Character is {0}", new String[] {Utility.hex(chr)}, e);
            }
        }

        private void printAndRecordCodePoint(
                boolean show, String chr, int cp, int ns, int nt, String comment) {
            try {
                // TODO: Do we need to record the new primary weight? -- addToSet(primaries, np, chr);
                addToSet(secondaries, ns, chr);
                addToSet(tertiaries, nt, chr);

                sb.setLength(0);
                if (show) {
                    sb.append(Utility.hex(chr)).append(";\t");
                }

                sb.append("[U+").append(Utility.hex(cp));
                if (ns != 0x500) {
                    sb.append(", ");
                    Fractional.hexBytes(ns, sb);
                }
                if (ns != 0x500 || nt != 5) {
                    sb.append(", ");
                    Fractional.hexBytes(nt, sb);
                }
                sb.append(']');

                if (comment != null) {
                    sb.append('\t').append(comment).append('\n');
                }

                fractionalLog.print(sb);
            } catch (Exception e) {
                throw new ChainException("Character is {0}", new String[] {Utility.hex(chr)}, e);
            }
        }

        /**
         * Inserts the script-first primary.
         * It only exists in FractionalUCA, there is no UCA primary for it.
         */
        private void printAndRecordScriptFirstPrimary(
                boolean show, int reorderCode, boolean startsGroup, int firstPrimary) {
            String comment = String.format(
                    "# %s first primary",
                    ReorderCodes.getName(reorderCode));
            if (startsGroup) {
                comment = comment + " starts reordering group";
                if (ps2f.isCompressibleFractionalPrimary(firstPrimary)) {
                    comment = comment + " (compressible)";
                }
            }
            String sampleChar = ReorderCodes.getSampleCharacter(reorderCode);
            printAndRecord(
                    true,
                    "\uFDD1" + sampleChar,
                    firstPrimary,
                    0x500,
                    5,
                    comment);
            fractionalLog.println();
        }
    }

    static void writeFractionalUCA(String filename) throws IOException {
        // TODO: This function and most class fields should not be static,
        // so that use of this class for different data cannot cause values from
        // one run to bleed into the next.

        /* We do not compute fractional implicit weights any more.
        FractionalUCA.checkImplicit();
        */
        FractionalUCA.checkFixes();

        UCA uca = getCollator();
        StringBuilder topByteInfo = new StringBuilder();
        PrimariesToFractional ps2f =
                new PrimariesToFractional(uca).assignFractionalPrimaries(topByteInfo);

        FractionalUCA.HighByteToReorderingToken highByteToScripts =
                new FractionalUCA.HighByteToReorderingToken(ps2f);
        Map<Integer, String> reorderingTokenOverrides = new TreeMap<Integer, String>();

        RoBitSet secondarySet = uca.getStatistics().getSecondarySet();

        FractionalStatistics fractionalStatistics = new FractionalStatistics(ps2f);

        int subtotal = 0;
        System.out.println("Fixing Secondaries");
        FractionalUCA.compactSecondary = new int[secondarySet.size()];
        for (int secondary = 0; secondary < FractionalUCA.compactSecondary.length; ++secondary) {
            if (secondarySet.get(secondary)) {
                FractionalUCA.compactSecondary[secondary] = subtotal++;
                /*System.out.println("compact[" + Utility.hex(secondary)
                        + "]=" + Utility.hex(compactSecondary[secondary])
                        + ", " + Utility.hex(fixSecondary(secondary)));*/
            }
        }
        System.out.println();

        //TO DO: find secondaries that don't overlap, and reassign

        // now translate!!

        // add special reordering tokens
        for (int reorderCode = ReorderCodes.FIRST; reorderCode < ReorderCodes.LIMIT; ++reorderCode) {
            int nextCode = reorderCode == ReorderCodes.DIGIT ? UCD_Types.LATIN_SCRIPT : reorderCode + 1;
            int start = Fractional.getLeadByte(ps2f.getFirstFractionalPrimary(reorderCode));
            int limit = Fractional.getLeadByte(ps2f.getFirstFractionalPrimary(nextCode));
            String token = ReorderCodes.getNameForSpecial(reorderCode);
            for (int i = start; i < limit; ++i) {
                reorderingTokenOverrides.put(i, token);
            }
        }

        Utility.fixDot();
        System.out.println("Writing");

        String directory = UCA.getUCA_GEN_DIR() + "CollationAuxiliary" + File.separator;

        boolean shortPrint = false;
        PrintWriter longLog = Utility.openPrintWriter(directory, filename + ".txt", Utility.UTF8_WINDOWS);
        if (shortPrint) { 
            PrintWriter shortLog = Utility.openPrintWriter(directory, filename + "_SHORT.txt", Utility.UTF8_WINDOWS);
    
            fractionalLog = new PrintWriter(new DualWriter(shortLog, longLog));
        } else {
            fractionalLog = longLog;
        }

        String summaryFileName = filename + "_summary.txt";
        PrintWriter summary = Utility.openPrintWriter(directory, summaryFileName, Utility.UTF8_WINDOWS);

        String logFileName = filename + "_log.txt";
        PrintWriter log = Utility.openPrintWriter(UCA.getUCA_GEN_DIR() + "log" + File.separator, logFileName, Utility.UTF8_WINDOWS);
        //PrintWriter summary = new PrintWriter(new BufferedWriter(new FileWriter(directory + filename + "_summary.txt"), 32*1024));

        summary.println("# Summary of Fractional UCA Table, generated from standard UCA");
        WriteCollationData.writeVersionAndDate(summary, summaryFileName, true);
        summary.println("# Primary Ranges");

        StringBuffer oldStr = new StringBuffer();

        OldEquivalenceClass secEq = new OldEquivalenceClass("\r\n#", 2, true);
        OldEquivalenceClass terEq = new OldEquivalenceClass("\r\n#", 2, true);
        String[] sampleEq = new String[0x200];
        int[] sampleLen = new int[0x200];

        int oldFirstPrimary = 0;

        fractionalLog.println("# Fractional UCA Table, generated from standard UCA");
        fractionalLog.println("# " + WriteCollationData.getNormalDate());
        fractionalLog.println("# VERSION: UCA=" + getCollator().getDataVersion() + ", UCD=" + getCollator().getUCDVersion());
        fractionalLog.println("# For a description of the format and usage, see");
        fractionalLog.println("#   http://www.unicode.org/reports/tr35/tr35-collation.html");
        fractionalLog.println();
        fractionalLog.println("[UCA version = " + getCollator().getDataVersion() + "]");

        printUnifiedIdeographRanges(fractionalLog);
        fractionalLog.println();

        // Print the [top_byte] information before any of the mappings
        // so that parsers can use this data while working with the fractional primary weights,
        // in particular the COMPRESS bits.
        fractionalLog.println("# Top Byte => Reordering Tokens");
        fractionalLog.print(topByteInfo);
        fractionalLog.println();

        String lastChr = "";
        int lastNp = 0;

        FractionalUCA.MinMaxFCE firstTertiaryIgnorable = new FractionalUCA.MinMaxFCE(false, "tertiary ignorable");
        FractionalUCA.MinMaxFCE lastTertiaryIgnorable = new FractionalUCA.MinMaxFCE(true, "tertiary ignorable");

        FractionalUCA.MinMaxFCE firstSecondaryIgnorable = new FractionalUCA.MinMaxFCE(false, "secondary ignorable");
        FractionalUCA.MinMaxFCE lastSecondaryIgnorable = new FractionalUCA.MinMaxFCE(true, "secondary ignorable");

        FractionalUCA.MinMaxFCE firstTertiaryInSecondaryNonIgnorable = new FractionalUCA.MinMaxFCE(false, "tertiary in secondary non-ignorable");
        FractionalUCA.MinMaxFCE lastTertiaryInSecondaryNonIgnorable = new FractionalUCA.MinMaxFCE(true, "tertiary in secondary non-ignorable");

        FractionalUCA.MinMaxFCE firstPrimaryIgnorable = new FractionalUCA.MinMaxFCE(false, "primary ignorable");
        FractionalUCA.MinMaxFCE lastPrimaryIgnorable = new FractionalUCA.MinMaxFCE(true, "primary ignorable");

        FractionalUCA.MinMaxFCE firstSecondaryInPrimaryNonIgnorable = new FractionalUCA.MinMaxFCE(false, "secondary in primary non-ignorable");
        FractionalUCA.MinMaxFCE lastSecondaryInPrimaryNonIgnorable = new FractionalUCA.MinMaxFCE(true, "secondary in primary non-ignorable");

        FractionalUCA.MinMaxFCE firstVariable = new FractionalUCA.MinMaxFCE(false, "variable");
        FractionalUCA.MinMaxFCE lastVariable = new FractionalUCA.MinMaxFCE(true, "variable");

        FractionalUCA.MinMaxFCE firstNonIgnorable = new FractionalUCA.MinMaxFCE(false, "regular");
        FractionalUCA.MinMaxFCE lastNonIgnorable = new FractionalUCA.MinMaxFCE(true, "regular");

        FractionalUCA.MinMaxFCE firstImplicitFCE = new FractionalUCA.MinMaxFCE(false, "implicit");
        FractionalUCA.MinMaxFCE lastImplicitFCE = new FractionalUCA.MinMaxFCE(true, "implicit");

        FractionalUCA.MinMaxFCE firstTrailing = new FractionalUCA.MinMaxFCE(false, "trailing");
        FractionalUCA.MinMaxFCE lastTrailing = new FractionalUCA.MinMaxFCE(true, "trailing");

        Map<Integer, Pair> fractBackMap = new TreeMap<Integer, Pair>();

        //int topVariable = -1;

        SortedSet<MappingWithSortKey> ordered = new MappingsForFractionalUCA(uca).getMappings();
        for (MappingWithSortKey mapping : ordered) {
            String chr = mapping.getString();
            CEList ces = mapping.getCEs();
            oldStr.setLength(0);
            PrimaryToFractional props = null;

            // TODO: Should we print and record the unmodified mapping.ces?
            if (!ces.isEmpty()) {
                int firstPrimary = CEList.getPrimary(ces.at(0));
                // String message = null;
                if (firstPrimary != oldFirstPrimary) {
                    fractionalLog.println();
                    oldFirstPrimary = firstPrimary;
                }
    
                props = ps2f.getPropsPinHan(firstPrimary);
                int firstFractional = props.getAndResetScriptFirstPrimary();
                if (firstFractional != 0) {
                    int reorderCode = props.getReorderCodeIfFirst();
    
                    fractionalStatistics.printAndRecordScriptFirstPrimary(
                            true, reorderCode,
                            props.isFirstInReorderingGroup(),
                            firstFractional);
    
                    // Record script-first primaries with the scripts of their sample characters.
                    String sampleChar = ReorderCodes.getSampleCharacter(reorderCode);
                    highByteToScripts.addScriptsIn(firstFractional, sampleChar);
    
                    // Print script aliases that have distinct sample characters.
                    if (reorderCode == UCD_Types.Meroitic_Cursive) {
                        // Mero = Merc
                        fractionalStatistics.printAndRecordScriptFirstPrimary(
                                true, UCD_Types.Meroitic_Hieroglyphs, false, firstFractional);
                    } else if (reorderCode == UCD_Types.HIRAGANA_SCRIPT) {
                        // Kana = Hrkt = Hira
                        fractionalStatistics.printAndRecordScriptFirstPrimary(
                                true, UCD_Types.KATAKANA_SCRIPT, false, firstFractional);
                        // Note: Hrkt = Hira but there is no sample character for Hrkt
                        // in CLDR scriptMetadata.txt.
                    }
                    // Note: Hans = Hant = Hani but we do not print Hans/Hant here because
                    // they have the same script sample character as Hani,
                    // and we cannot write multiple mappings for the same string.
    
                    if (reorderCode == ReorderCodes.DIGIT) {
                        fractionalStatistics.printAndRecord(
                                true, "\uFDD04",
                                ps2f.getNumericFractionalPrimary(), 0x500, 5,
                                "# lead byte for numeric sorting");
                        fractionalLog.println();
                        highByteToScripts.addScriptsIn(ps2f.getNumericFractionalPrimary(), "4");
                    }
                }
            }

            if (mapping.hasPrefix()) {
                fractionalLog.print(Utility.hex(mapping.getPrefix()) + " | ");
            }
            fractionalLog.print(Utility.hex(chr) + "; ");

            // In order to support continuation CEs (as for implicit primaries),
            // we need a flag for the first variable-length CE rather than just testing q==0.
            boolean isFirst = true;

            for (int q = 0;; ++q) {
                if (q == ces.length()) {
                    if (q == 0) {
                        // chr maps to nothing.
                        fractionalLog.print("[,,]");
                        oldStr.append(ces);
                    }
                    break;
                }

                int ce = ces.at(q);
                int pri = CEList.getPrimary(ce);
                int sec = CEList.getSecondary(ce); 
                int ter = CEList.getTertiary(ce);

                oldStr.append(CEList.toString(ce));// + "," + Integer.toString(ce,16);

                if (sec != 0x20) {
                    /* boolean changed = */ secEq.add(new Integer(sec), new Integer(pri));
                }
                if (ter != 0x2) {
                    /* boolean changed = */ terEq.add(new Integer(ter), new Integer((pri << 16) | sec));
                }

                if (sampleEq[sec] == null || sampleLen[sec] > ces.length()) {
                    sampleEq[sec] = chr;
                    sampleLen[sec] = ces.length();
                }
                if (sampleEq[ter] == null || sampleLen[ter] > ces.length()) {
                    sampleEq[ter] = chr;
                    sampleLen[ter] = ces.length();
                }

                // props was fetched for the firstPrimary before the loop.
                if (q != 0) {
                    props = ps2f.getPropsPinHan(pri);
                }
                int np = props.getFractionalPrimary();

                // special treatment for unsupported!
                if (UCA.isImplicitLeadPrimary(pri)) {
                    if (DEBUG) {
                        System.out.println("DEBUG: " + ces
                                + ", Current: " + q + ", " + Default.ucd().getCodeAndName(chr));
                    }
                    ++q;
                    oldStr.append(CEList.toString(ces.at(q)));// + "," + Integer.toString(ces.at(q),16);

                    int pri2 = CEList.getPrimary(ces.at(q));
                    int implicitCodePoint = UCA.ImplicitToCodePoint(pri, pri2);
                    if (DEBUG) {
                        System.out.println("Computing Unsupported CP as: "
                                + Utility.hex(pri)
                                + ", " + Utility.hex(pri2)
                                + " => " + Utility.hex(implicitCodePoint));
                    }

                    /* We do not compute fractional implicit weights any more.
                    // was: np = FractionalUCA.getImplicitPrimary(pri);
                    */
                    int ns = FractionalUCA.fixSecondary(sec);
                    int nt = FractionalUCA.fixTertiary(ter, chr);

                    fractionalStatistics.printAndRecordCodePoint(false, chr, implicitCodePoint, ns, nt, null);
                } else {
                    // pri is a non-implicit UCA primary.
                    if (isFirst) { // only look at first one
                        highByteToScripts.addScriptsIn(np, chr);
                    }
    
                    if (pri == 0) {
                        if (chr.equals("\u01C6")) {
                            System.out.println("At dz-caron");
                        }
                        Integer key = new Integer(ce);
                        Pair value = fractBackMap.get(key);
                        if (value == null
                                || (ces.length() < ((Integer)(value.first)).intValue())) {
                            fractBackMap.put(key, new Pair(ces.length(), chr));
                        }
                    }

                    int ns = FractionalUCA.fixSecondary(sec);
                    int nt = FractionalUCA.fixTertiary(ter, chr);

                    // TODO: add the prefix to the stats?
                    fractionalStatistics.printAndRecord(false, chr, np, ns, nt, null);

                    // RECORD STATS
                    // but ONLY if we are not part of an implicit

                    if (np != 0) {
                        firstSecondaryInPrimaryNonIgnorable.setValue(0, ns, 0, chr);
                        lastSecondaryInPrimaryNonIgnorable.setValue(0, ns, 0, chr);
                    }
                    if (ns != 0) {
                        firstTertiaryInSecondaryNonIgnorable.setValue(0, 0, nt & 0x3F, chr);
                        lastTertiaryInSecondaryNonIgnorable.setValue(0, 0, nt & 0x3F, chr);
                    }
                    if (np == 0 && ns == 0) {
                        firstSecondaryIgnorable.setValue(np, ns, nt, chr);
                        lastSecondaryIgnorable.setValue(np, ns, nt, chr); 
                    } else if (np == 0) {
                        firstPrimaryIgnorable.setValue(np, ns, nt, chr);
                        lastPrimaryIgnorable.setValue(np, ns, nt, chr); 
                    } else if (getCollator().isVariable(ce)) {
                        firstVariable.setValue(np, ns, nt, chr);
                        lastVariable.setValue(np, ns, nt, chr); 
                    } else if (pri > UCA_Types.UNSUPPORTED_LIMIT) {        // Trailing (none currently)
                        System.out.println("Trailing: " 
                                + Default.ucd().getCodeAndName(chr) + ", "
                                + CEList.toString(ce) + ", " 
                                + Utility.hex(pri) + ", " 
                                + Utility.hex(UCA_Types.UNSUPPORTED_LIMIT));
                        firstTrailing.setValue(np, ns, nt, chr);
                        lastTrailing.setValue(np, ns, nt, chr); 
                    } else {
                        firstNonIgnorable.setValue(np, ns, nt, chr);
                        lastNonIgnorable.setValue(np, ns, nt, chr); 
                    }
                }

                if (isFirst) {
                    if (!FractionalUCA.sameTopByte(np, lastNp)) {
                        if (lastNp != 0) {
                            showRange("Last", summary, lastChr, lastNp);
                        }
                        summary.println();
                        showRange("First", summary, chr, np);
                    }
                    lastNp = np;
                    isFirst = false;
                }
            }
            String name = UTF16.hasMoreCodePointsThan(chr, 1) 
                    ? Default.ucd().getName(UTF16.charAt(chr, 0)) + " ..."
                            : Default.ucd().getName(chr);

            String gcInfo = getStringTransform(chr, "/", ScriptTransform);
            String scriptInfo = getStringTransform(chr, "/", GeneralCategoryTransform);

            longLog.print("\t# " + gcInfo + " " + scriptInfo + "\t" + oldStr + "\t* " + name);
            fractionalLog.println();
            lastChr = chr;
        }

        fractionalLog.println();
        fractionalLog.println("# SPECIAL MAX/MIN COLLATION ELEMENTS");
        fractionalLog.println();

        fractionalStatistics.printAndRecord(true, "\uFFFE", 0x020000, 2, 2, "# Special LOWEST primary, for merge/interleaving");
        fractionalStatistics.printAndRecord(true, "\uFFFF", 0xEFFF00, 5, 5, "# Special HIGHEST primary, for ranges");

        fractionalLog.println();

        // ADD HOMELESS COLLATION ELEMENTS
        fractionalLog.println();
        fractionalLog.println("# HOMELESS COLLATION ELEMENTS");

        FakeString fakeString = new FakeString();
        Iterator<Integer> it3 = fractBackMap.keySet().iterator();
        while (it3.hasNext()) {
            Integer key = (Integer) it3.next();
            Pair pair = fractBackMap.get(key);
            if (((Integer)pair.first).intValue() < 2) {
                continue;
            }
            String sample = (String)pair.second;

            int ce = key.intValue();

            PrimaryToFractional props = ps2f.getPropsPinHan(CEList.getPrimary(ce));
            int np = props.getFractionalPrimary();
            int ns = FractionalUCA.fixSecondary(CEList.getSecondary(ce));
            int nt = FractionalUCA.fixTertiary(CEList.getTertiary(ce), sample);

            highByteToScripts.addScriptsIn(np, sample);
            fractionalStatistics.printAndRecord(true, fakeString.next(), np, ns, nt, null);

            longLog.print("\t# " + getCollator().getCEList(sample, true) + "\t* " + Default.ucd().getCodeAndName(sample));
            fractionalLog.println();
        }

        // Since the UCA doesn't have secondary ignorables, fake them.
        int fakeTertiary = 0x3F03;
        if (firstSecondaryIgnorable.isUnset()) {
            System.out.println("No first/last secondary ignorable: resetting to HARD CODED, adding homeless");
            //long bound = lastTertiaryInSecondaryNonIgnorable.getValue(2);
            firstSecondaryIgnorable.setValue(0,0,fakeTertiary,"");
            lastSecondaryIgnorable.setValue(0,0,fakeTertiary,"");
            System.out.println(firstSecondaryIgnorable.formatFCE());
            // also add homeless
            fractionalStatistics.printAndRecord(true, fakeString.next(), 0, 0, fakeTertiary, null);

            fractionalLog.println("\t# CONSTRUCTED FAKE SECONDARY-IGNORABLE");
        }

        int firstImplicit = Fractional.IMPLICIT_BASE_BYTE;  // was FractionalUCA.getImplicitPrimary(UCD_Types.CJK_BASE);
        int lastImplicit = Fractional.IMPLICIT_MAX_BYTE;  // was FractionalUCA.getImplicitPrimary(0x10FFFD);

        fractionalLog.println();
        fractionalLog.println("# VALUES BASED ON UCA");

        if (firstTertiaryIgnorable.isUnset()) {
            firstTertiaryIgnorable.setValue(0,0,0,"");
            lastTertiaryIgnorable.setValue(0,0,0,"");
            System.out.println(firstSecondaryIgnorable.formatFCE());
        }

        fractionalLog.println(firstTertiaryIgnorable);
        fractionalLog.println(lastTertiaryIgnorable);

        fractionalLog.println("# Warning: Case bits are masked in the following");

        fractionalLog.println(firstTertiaryInSecondaryNonIgnorable.toString(true));
        fractionalLog.println(lastTertiaryInSecondaryNonIgnorable.toString(true));

        fractionalLog.println(firstSecondaryIgnorable);
        fractionalLog.println(lastSecondaryIgnorable);

        if (lastTertiaryInSecondaryNonIgnorable.getValue(2) >= firstSecondaryIgnorable.getValue(2)) {
            fractionalLog.println("# FAILURE: Overlap of tertiaries");
        }

        fractionalLog.println(firstSecondaryInPrimaryNonIgnorable.toString(true));
        fractionalLog.println(lastSecondaryInPrimaryNonIgnorable.toString(true));

        fractionalLog.println(firstPrimaryIgnorable);
        fractionalLog.println(lastPrimaryIgnorable);

        if (lastSecondaryInPrimaryNonIgnorable.getValue(1) >= firstPrimaryIgnorable.getValue(1)) {
            fractionalLog.println("# FAILURE: Overlap of secondaries");
        }

        fractionalLog.println(firstVariable);
        fractionalLog.println(lastVariable);

        long firstSymbolPrimary = MinMaxFCE.fixWeight(ps2f.getFirstFractionalPrimary(ReorderCodes.SYMBOL));
        fractionalLog.println("[variable top = " + Fractional.hexBytes((firstSymbolPrimary & 0xff000000) - 1) + "]");

        fractionalLog.println(firstNonIgnorable);
        fractionalLog.println(lastNonIgnorable);

        firstImplicitFCE.setValue(firstImplicit, Fractional.COMMON_SEC, Fractional.COMMON_TER, "");
        lastImplicitFCE.setValue(lastImplicit, Fractional.COMMON_SEC, Fractional.COMMON_TER, "");

        fractionalLog.println(firstImplicitFCE); // "[first implicit " + (new FCE(false,firstImplicit, COMMON<<24, COMMON<<24)).formatFCE() + "]");
        fractionalLog.println(lastImplicitFCE); // "[last implicit " + (new FCE(false,lastImplicit, COMMON<<24, COMMON<<24)).formatFCE() + "]");

        if (firstTrailing.isUnset()) {
            System.out.println("No first/last trailing: resetting");
            firstTrailing.setValue(Fractional.IMPLICIT_MAX_BYTE+1, Fractional.COMMON_SEC, Fractional.COMMON_TER, "");
            lastTrailing.setValue(Fractional.IMPLICIT_MAX_BYTE+1, Fractional.COMMON_SEC, Fractional.COMMON_TER, "");
            System.out.println(firstTrailing.formatFCE());        
        }

        fractionalLog.println(firstTrailing);
        fractionalLog.println(lastTrailing);
        fractionalLog.println();

        //        fractionalLog.println("# Distinguished Variable-Top Values: the last of each range");
        //
        //        fractionalLog.println("[vt-none " + hexBytes(3) + "]");
        //        fractionalLog.println("[vt-space " + hexBytes(FractionalUCA.primaryDelta[spaceRange.getMaximum()]) + "]");
        //        fractionalLog.println("[vt-punct " + hexBytes(FractionalUCA.primaryDelta[punctuationRange.getMaximum()]) + "]");
        //        fractionalLog.println("[vt-symbol " + hexBytes(FractionalUCA.primaryDelta[symbolRange.getMaximum()]) + "]");
        //        fractionalLog.println("[vt-currency " + hexBytes(FractionalUCA.primaryDelta[currencyRange.getMaximum()]) + "]");
        //        fractionalLog.println("[vt-digit " + hexBytes(FractionalUCA.primaryDelta[digitRange.getMaximum()]) + "]");
        //        fractionalLog.println("[vt-ducet " + hexBytes(FractionalUCA.primaryDelta[ducetFirstNonVariable]) + "]");
        //        fractionalLog.println();

        highByteToScripts.cleanup(reorderingTokenOverrides);

        fractionalLog.println("# Reordering Tokens => Top Bytes");
        fractionalLog.println(highByteToScripts.getInfo(true));
        fractionalLog.println();

        fractionalLog.println("# General Categories => Top Byte");
        fractionalLog.println(highByteToScripts.getInfo(false));
        fractionalLog.println();


        fractionalLog.println();
        fractionalLog.println("# FIXED VALUES");

        // fractionalLog.println("# superceded! [top "  + lastNonIgnorable.formatFCE() + "]");
        fractionalLog.println("[fixed first implicit byte " + Utility.hex(Fractional.IMPLICIT_BASE_BYTE,2) + "]");
        fractionalLog.println("[fixed last implicit byte " + Utility.hex(Fractional.IMPLICIT_MAX_BYTE,2) + "]");
        fractionalLog.println("[fixed first trail byte " + Utility.hex(Fractional.IMPLICIT_MAX_BYTE+1,2) + "]");
        fractionalLog.println("[fixed last trail byte " + Utility.hex(Fractional.SPECIAL_BASE-1,2) + "]");
        fractionalLog.println("[fixed first special byte " + Utility.hex(Fractional.SPECIAL_BASE,2) + "]");
        fractionalLog.println("[fixed last special byte " + Utility.hex(0xFF,2) + "]");

        showRange("Last", summary, lastChr, lastNp);
        //summary.println("Last:  " + Utility.hex(lastNp) + ", " + WriteCollationData.ucd.getCodeAndName(UTF16.charAt(lastChr, 0)));

        /*
            String sample = "\u3400\u3401\u4DB4\u4DB5\u4E00\u4E01\u9FA4\u9FA5\uAC00\uAC01\uD7A2\uD7A3";
            for (int i = 0; i < sample.length(); ++i) {
                char ch = sample.charAt(i);
                log.println(Utility.hex(ch) + " => " + Utility.hex(fixHan(ch))
                        + "          " + ucd.getName(ch));
            }
         */
        summary.println();
        /* We do not compute fractional implicit weights any more.
        summary.println("# First Implicit: " + Utility.hex(Fractional.IMPLICIT_BASE_BYTE));  // was FractionalUCA.getImplicitPrimary(0)
        summary.println("# Last Implicit: " + Utility.hex(Fractional.IMPLICIT_MAX_BYTE));  // was FractionalUCA.getImplicitPrimary(0x10FFFF)
        summary.println("# First CJK: " + Utility.hex(Fractional.IMPLICIT_BASE_BYTE));  // was FractionalUCA.getImplicitPrimary(0x4E00)
        summary.println("# Last CJK: " + Utility.hex(Fractional.IMPLICIT_BASE_BYTE));  // was FractionalUCA.getImplicitPrimary(0xFA2F), should have been dynamic
        summary.println("# First CJK_A: " + Utility.hex(Fractional.IMPLICIT_BASE_BYTE + 1));  // was FractionalUCA.getImplicitPrimary(0x3400)
        summary.println("# Last CJK_A: " + Utility.hex(Fractional.IMPLICIT_BASE_BYTE + 1));  // was FractionalUCA.getImplicitPrimary(0x4DBF), should have been dynamic
        */

        if (DEBUG) {
            /* We do not compute fractional implicit weights any more.
            boolean lastOne = false;
            for (int i = 0; i < 0x10FFFF; ++i) {
                boolean thisOne = UCD.isCJK_BASE(i) || UCD.isCJK_AB(i);
                if (thisOne != lastOne) {
                    summary.println("# Implicit Cusp: CJK=" + lastOne + ": " + Utility.hex(i-1) +
                            " => " + Utility.hex(FractionalUCA.Variables.INT_MASK &
                                                 FractionalUCA.getImplicitPrimary(i-1)));
                    summary.println("# Implicit Cusp: CJK=" + thisOne + ": " + Utility.hex(i) +
                            " => " + Utility.hex(FractionalUCA.Variables.INT_MASK &
                                                 FractionalUCA.getImplicitPrimary(i)));
                    lastOne = thisOne;
                }
            }
            */
            summary.println("Compact Secondary 153: " + FractionalUCA.compactSecondary[0x153]);
            summary.println("Compact Secondary 157: " + FractionalUCA.compactSecondary[0x157]);
        }



        summary.println();
        summary.println("# Disjoint classes for Secondaries");
        summary.println("#" + secEq.toString());

        summary.println();
        summary.println("# Disjoint classes for Tertiaries");
        summary.println("#" + terEq.toString());

        summary.println();
        summary.println("# Example characters for each TERTIARY value");
        summary.println();
        summary.println("# UCA : (FRAC) CODE [    UCA CE    ] Name");
        summary.println();

        for (int i = 0; i < sampleEq.length; ++i) {
            if (sampleEq[i] == null) {
                continue;
            }
            if (i == 0x20) {
                summary.println();
                summary.println("# Example characters for each SECONDARY value");
                summary.println();
                summary.println("# UCA : (FRAC) CODE [    UCA CE    ] Name");
                summary.println();
            }
            CEList ces = getCollator().getCEList(sampleEq[i], true);
            int newval = i < 0x20 ? FractionalUCA.fixTertiary(i,sampleEq[i]) : FractionalUCA.fixSecondary(i);
            summary.print("# " + Utility.hex(i) + ": (" + Utility.hex(newval) + ") "
                    + Utility.hex(sampleEq[i]) + " ");
            for (int q = 0; q < ces.length(); ++q) {
                summary.print(CEList.toString(ces.at(q)));
            }
            summary.println(" " + Default.ucd().getName(sampleEq[i]));

        }
        fractionalStatistics.show(log);
        fractionalLog.close();
        summary.close();
        log.close();
    }

    private static class FakeString {
        char[] buffer = {'\uFDD0', '@'};
        String next() {
            buffer[1]++;
            return new String(buffer);
        }
    }

    private static UCA getCollator() {
        return WriteCollationData.getCollator(CollatorType.cldrWithoutFFFx);
    }

    static Transform<Integer, String> ScriptTransform = new Transform<Integer, String>() {
        public String transform(Integer codePoint) {
            return Default.ucd().getScriptID(codePoint, UCD_Types.SHORT);
        }
    };

    static Transform<Integer, String> GeneralCategoryTransform  = new Transform<Integer, String>() {
        public String transform(Integer codePoint) {
            return Default.ucd().getCategoryID(codePoint, UCD_Types.SHORT);
        }
    };

    //    static class IcuEnumProp implements Transform<Integer, String> {
    //        private int propEnum;
    //        private int nameChoice;
    //
    //        /**
    //         * @param propEnum
    //         * @param nameChoice
    //         */
    //        public IcuEnumProp(int propEnum, int nameChoice) {
    //            super();
    //            this.propEnum = propEnum;
    //            this.nameChoice = nameChoice;
    //        }
    //
    //        public String transform(Integer source) {
    //            return propValue(source, propEnum, nameChoice);
    //        }
    //    }

    //  private static String propValue(int ch, int propEnum, int nameChoice) {
    //  return UCharacter.getPropertyValueName(propEnum, UCharacter.getIntPropertyValue(ch, propEnum), nameChoice);
    //}


    public static String getStringTransform(CharSequence string, CharSequence separator, Transform<Integer,String> prop) {
        return getStringTransform(string, separator, prop, new ArrayList<String>());
    }

    public static String getStringTransform(CharSequence string, CharSequence separator, Transform<Integer,String> prop, Collection<String> c) {
        c.clear();
        int cp;
        for (int i = 0; i < string.length(); i += Character.charCount(cp)) {
            cp = Character.codePointAt(string, i);
            c.add(prop.transform(cp));
        }
        StringBuffer result = new StringBuffer();
        for (String item : c) {
            if (result.length() != 0) {
                result.append(separator);
            }
            result.append(item);
        }
        return result.toString();
    }

    private static void showRange(String title, PrintWriter summary, String lastChr, int lastNp) {
        int ch = lastChr.codePointAt(0);
        summary.println("#\t" + title
                + "\t" + padHexBytes(lastNp) 
                + "\t" + ScriptTransform.transform(ch)
                + "\t" + GeneralCategoryTransform.transform(ch)
                + "\t" + Default.ucd().getCodeAndName(UTF16.charAt(lastChr,0)));
    }

    private static String padHexBytes(int lastNp) {
        String result = Fractional.hexBytes(lastNp & FractionalUCA.Variables.INT_MASK);
        return result + Utility.repeat(" ", 11-result.length());
        // E3 9B 5F C8
    }

    /* We do not compute fractional implicit weights any more.
    static void checkImplicit() {
        System.out.println("Starting Implicit Check");

        long oldPrimary = 0;
        int oldChar = -1;
        int oldSwap = -1;

        // test monotonically increasing

        for (int i = 0; i < 0x21FFFF; ++i) {
            long newPrimary = FractionalUCA.Variables.INT_MASK & FractionalUCA.getImplicitPrimaryFromSwapped(i);
            if (newPrimary < oldPrimary) {
                throw new IllegalArgumentException(Utility.hex(i) + ": overlap: "
                        + Utility.hex(oldChar) + " (" + Utility.hex(oldPrimary) + ")"
                        + " > " + Utility.hex(i) + "(" + Utility.hex(newPrimary) + ")");
            }
            oldPrimary = newPrimary;
        }

        FractionalUCA.showImplicit("# 3B9D", 0x3B9D);

        FractionalUCA.showImplicit("# First CJK", UCD_Types.CJK_BASE);
        FractionalUCA.showImplicit("# Last CJK", UCD_Types.CJK_LIMIT-1);
        FractionalUCA.showImplicit("# First CJK-compat", UCD_Types.CJK_COMPAT_USED_BASE);
        FractionalUCA.showImplicit("# Last CJK-compat", UCD_Types.CJK_COMPAT_USED_LIMIT-1);
        FractionalUCA.showImplicit("# First CJK_A", UCD_Types.CJK_A_BASE);
        FractionalUCA.showImplicit("# Last CJK_A", UCD_Types.CJK_A_LIMIT-1);
        FractionalUCA.showImplicit("# First CJK_B", UCD_Types.CJK_B_BASE);
        FractionalUCA.showImplicit("# Last CJK_B", UCD_Types.CJK_B_LIMIT-1);
        FractionalUCA.showImplicit("# First CJK_C", UCD_Types.CJK_C_BASE);
        FractionalUCA.showImplicit("# Last CJK_C", UCD_Types.CJK_C_LIMIT-1);
        FractionalUCA.showImplicit("# First CJK_D", UCD_Types.CJK_D_BASE);
        FractionalUCA.showImplicit("# Last CJK_D", UCD_Types.CJK_D_LIMIT-1);
        FractionalUCA.showImplicit("# First Other Implicit", 0);
        FractionalUCA.showImplicit("# Last Other Implicit", 0x10FFFF);

        FractionalUCA.showImplicit3("# FIRST", 0);
        FractionalUCA.showImplicit3("# Boundary-1", FractionalUCA.Variables.IMPLICIT_4BYTE_BOUNDARY-1);
        FractionalUCA.showImplicit3("# Boundary00", FractionalUCA.Variables.IMPLICIT_4BYTE_BOUNDARY);
        FractionalUCA.showImplicit3("# Boundary+1", FractionalUCA.Variables.IMPLICIT_4BYTE_BOUNDARY+1);
        FractionalUCA.showImplicit3("# LAST", 0x21FFFF);


        for (int batch = 0; batch < 3; ++batch) {
            // init each batch
            oldPrimary = 0;
            oldChar = -1;

            for (int i = 0; i <= 0x10FFFF; ++i) {

                // separate the three groups

                if (UCD.isCJK_BASE(i) || UCD_Types.CJK_COMPAT_USED_BASE <= i && i < UCD_Types.CJK_COMPAT_USED_LIMIT) {
                    if (batch != 0) {
                        continue;
                    }
                } else if (UCD.isCJK_AB(i)) {
                    if (batch != 1) {
                        continue;
                    }
                } else if (batch != 2) {
                    continue;
                }


                // test swapping

                int currSwap = ImplicitCEGenerator.swapCJK(i);
                if (currSwap < oldSwap) {
                    throw new IllegalArgumentException(Utility.hex(i) + ": overlap: "
                            + Utility.hex(oldChar) + " (" + Utility.hex(oldSwap) + ")"
                            + " > " + Utility.hex(i) + "(" + Utility.hex(currSwap) + ")");
                }


                long newPrimary = FractionalUCA.Variables.INT_MASK & FractionalUCA.getImplicitPrimary(i);

                // test correct values


                if (newPrimary < oldPrimary && oldChar != -1) {
                    throw new IllegalArgumentException(Utility.hex(i) + ": overlap: "
                            + Utility.hex(oldChar) + " (" + Utility.hex(oldPrimary) + ")"
                            + " > " + Utility.hex(i) + "(" + Utility.hex(newPrimary) + ")");
                }


                long b0 = (newPrimary >> 24) & 0xFF;
                long b1 = (newPrimary >> 16) & 0xFF;
                long b2 = (newPrimary >> 8) & 0xFF;
                long b3 = newPrimary & 0xFF;

                if (b0 < Fractional.IMPLICIT_BASE_BYTE || b0 > Fractional.IMPLICIT_MAX_BYTE  || b1 < 3 || b2 < 3 || b3 == 1 || b3 == 2) {
                    throw new IllegalArgumentException(Utility.hex(i) + ": illegal byte value: " + Utility.hex(newPrimary)
                            + ", " + Utility.hex(b1) + ", " + Utility.hex(b2) + ", " + Utility.hex(b3));
                }

                // print range to look at
                final boolean TESTING = false;
                if (TESTING) {
                    int b = i & 0xFF;
                    if (b == 255 || b == 0 || b == 1) {
                        System.out.println(Utility.hex(i) + " => " + Utility.hex(newPrimary));
                    }
                }
                oldPrimary = newPrimary;
                oldChar = i;
            }
        }
        System.out.println("Successful Implicit Check!!");
    }
    */

    static void checkFixes() {
        System.out.println("Checking Secondary/Tertiary Fixes");
        int lastVal = -1;
        for (int i = 0; i <= 0x16E; ++i) {
            if (i == 0x153) {
                System.out.println("debug");
            }
            int val = FractionalUCA.fixSecondary2(i, 999, 999); // HACK for UCA
            if (val <= lastVal) {
                throw new IllegalArgumentException(
                        "Unordered: " + Utility.hex(val) + " => " + Utility.hex(lastVal));
            }
            int top = val >>> 8;
        int bottom = val & 0xFF;
        if (top != 0 && (top < Fractional.COMMON_SEC || top > 0xEF)
                || (top > Fractional.COMMON_SEC && top < 0x87)
                || (bottom != 0 && (FractionalUCA.isEven(bottom) || bottom < Fractional.COMMON_SEC || bottom > 0xFD))
                || (bottom == 0 && top != 0 && FractionalUCA.isEven(top))) {
            throw new IllegalArgumentException("Secondary out of range: " + Utility.hex(i) + " => " 
                    + Utility.hex(top) + ", " + Utility.hex(bottom));
        }
        }

        lastVal = -1;
        for (int i = 0; i <= 0x1E; ++i) {
            if (i == 1 || i == 7) {
                continue; // never occurs
            }
            int val = FractionalUCA.fixTertiary(i, ""); // not interested in case bits, so ok to pass in ""
            val &= 0x7F; // mask off case bits
            if (val <= lastVal) {
                throw new IllegalArgumentException(
                        "Unordered: " + Utility.hex(val) + " => " + Utility.hex(lastVal));
            }
            if (val != 0 && (FractionalUCA.isEven(val) || val < Fractional.COMMON_TER || val > 0x3D)) {
                throw new IllegalArgumentException("Tertiary out of range: " + Utility.hex(i) + " => " 
                        + Utility.hex(val));
            }
        }
        System.out.println("END Checking Secondary/Tertiary Fixes");
    }

    /* We do not compute fractional implicit weights any more.
    static void showImplicit(String title, int cp) {
        if (DEBUG) {
            FractionalUCA.showImplicit2(title + "-1", cp-1);
        }

        FractionalUCA.showImplicit2(title + "00", cp);

        if (DEBUG) {
            FractionalUCA.showImplicit2(title + "+1", cp+1);
        }
    }

    static void showImplicit3(String title, int cp) {
        System.out.println("*" + title + ":\t" + Utility.hex(cp)
                + " => " + Utility.hex(FractionalUCA.Variables.INT_MASK & FractionalUCA.getImplicitPrimaryFromSwapped(cp)));
    }

    static void showImplicit2(String title, int cp) {
        System.out.println(title + ":\t" + Utility.hex(cp)
                + " => " + Utility.hex(ImplicitCEGenerator.swapCJK(cp))
                + " => " + Utility.hex(FractionalUCA.Variables.INT_MASK & FractionalUCA.getImplicitPrimary(cp)));
    }

    static int getImplicitPrimaryFromSwapped(int cp) {
        return FractionalUCA.implicit.getImplicitFromRaw(cp);
    }
    */

    static int fixSecondary(int x) {
        x = FractionalUCA.compactSecondary[x];
        return FractionalUCA.fixSecondary2(x, FractionalUCA.compactSecondary[0x153], FractionalUCA.compactSecondary[0x157]);
    }

    static int fixSecondary2(int x, int gap1, int gap2) {
        int top = x;
        int bottom = 0;
        if (top == 0) {
            // ok, zero
        } else if (top == 1) {
            top = Fractional.COMMON_SEC;
        } else {
            top *= 2; // create gap between elements. top is now 4 or more
            top += 0x80 + Fractional.COMMON_SEC - 2; // insert gap to make top at least 87

            if (top >= 149) top += 2; // HACK for backwards compatibility.

            // lowest values are singletons. Others are 2 bytes
            if (top > FractionalUCA.Variables.secondaryDoubleStart) {
                top -= FractionalUCA.Variables.secondaryDoubleStart;
                top *= 4; // leave bigger gap just in case
                if (x > gap1) {
                    top += 256; // leave gap after COMBINING ENCLOSING KEYCAP (see below)
                }
                if (x > gap2) {
                    top += 64; // leave gap after RUNIC LETTER SHORT-TWIG-AR A (see below)
                }

                bottom = (top % FractionalUCA.Variables.LAST_COUNT) * 2 + Fractional.COMMON_SEC;
                top = (top / FractionalUCA.Variables.LAST_COUNT) + FractionalUCA.Variables.secondaryDoubleStart;
            }
        }
        return (top << 8) | bottom;
    }

    static int fixTertiary(int x, String originalString) {
        if (x == 0) {
            return x;
        }
        if (x == 1 || x == 7) {
            throw new IllegalArgumentException("Tertiary illegal: " + x);
        }
        // 2 => COMMON, 1 is unused
        int y = x < 7 ? x : x - 1; // we now use 1F = MAX. Causes a problem so we shift everything to fill a gap at 7 (unused).

        int result = 2 * (y - 2) + Fractional.COMMON_TER;

        if (result >= 0x3E) {
            throw new IllegalArgumentException("Tertiary too large: "
                    + Utility.hex(x) + " => " + Utility.hex(result));
        }

        // get case bits. 00 is low, 01 is mixed (never happens), 10 is high
        int caseBits;
        if (GET_CASE_FROM_STRING) {
            caseBits = CaseBit.getPropertyCasing(originalString).getBits();
        } else {
            caseBits = CaseBit.getCaseFromTertiary(x).getBits();
        }
        if (caseBits != 0) {
            result |= caseBits;
        }
        return result;
    }

    static final boolean GET_CASE_FROM_STRING = false;

    static int[] compactSecondary;

    /* We do not compute fractional implicit weights any more.
    static int getImplicitPrimary(int cp) {
        return FractionalUCA.implicit.getImplicitFromCodePoint(cp);
    }
    */

    static boolean sameTopByte(int x, int y) {
        int x1 = x & 0xFF0000;
        int y1 = y & 0xFF0000;
        if (x1 != 0 || y1 != 0) {
            return x1 == y1;
        }
        x1 = x & 0xFF00;
        y1 = y & 0xFF00;
        return x1 == y1;
    }

    /* We do not compute fractional implicit weights any more.
    static ImplicitCEGenerator implicit = new ImplicitCEGenerator(Fractional.IMPLICIT_BASE_BYTE, Fractional.IMPLICIT_MAX_BYTE);
    */

    //    static final boolean needsCaseBit(String x) {
    //        String s = Default.nfkd().normalize(x);
    //        if (!Default.ucd().getCase(s, FULL, LOWER).equals(s)) {
    //            return true;
    //        }
    //        if (!CaseBit.toSmallKana(s).equals(s)) {
    //            return true;
    //        }
    //        return false;
    //    }

    /**
     * Prints the Unified_Ideograph ranges in collation order.
     * As a result, a parser need not have this data available for the current Unicode version.
     */
    private static void printUnifiedIdeographRanges(PrintWriter fractionalLog) {
        UnicodeSet hanSet =
                ToolUnicodePropertySource.make(getCollator().getUCDVersion()).
                getProperty("Unified_Ideograph").getSet("True");
        UnicodeSet coreHanSet = (UnicodeSet) hanSet.clone();
        coreHanSet.retain(0x4e00, 0xffff);  // BlockCJK_Unified_Ideograph or CJK_Compatibility_Ideographs
        StringBuilder hanSB = new StringBuilder("[Unified_Ideograph");
        UnicodeSetIterator hanIter = new UnicodeSetIterator(coreHanSet);
        while (hanIter.nextRange()) {
            int start = hanIter.codepoint;
            int end = hanIter.codepointEnd;
            hanSB.append(' ').append(Utility.hex(start));
            if (start < end) {
                hanSB.append("..").append(Utility.hex(end));
            }
        }
        hanSet.remove(0x4e00, 0xffff);
        hanIter.reset(hanSet);
        while (hanIter.nextRange()) {
            int start = hanIter.codepoint;
            int end = hanIter.codepointEnd;
            hanSB.append(' ').append(Utility.hex(start));
            if (start < end) {
                hanSB.append("..").append(Utility.hex(end));
            }
        }
        hanSB.append("]");
        fractionalLog.println(hanSB);
    }

    private static boolean isEven(int x) {
        return (x & 1) == 0;
    }
}
