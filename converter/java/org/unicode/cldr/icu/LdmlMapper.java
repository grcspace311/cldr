package org.unicode.cldr.icu;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.unicode.cldr.draft.FileUtilities;
import org.unicode.cldr.util.CLDRFile;
import org.unicode.cldr.util.RegexLookup;
import org.unicode.cldr.util.CldrUtility.Output;
import org.unicode.cldr.util.CldrUtility.VariableReplacer;
import org.unicode.cldr.util.RegexLookup.Finder;
import org.unicode.cldr.util.RegexLookup.Merger;
import org.unicode.cldr.util.RegexLookup.RegexFinder;

import com.ibm.icu.impl.Row.R2;
import com.ibm.icu.impl.Row.R3;
import com.ibm.icu.text.Transform;
import com.ibm.icu.text.UnicodeSet;

public abstract class LdmlMapper {
    private static final Pattern SEMI = Pattern.compile("\\s*+;\\s*+");

    private String converterFile;

    protected static class FullMatcher extends RegexFinder {
        public FullMatcher(String pattern) {
            super(pattern);
        }
        
        public boolean find(String item, Object context) {
            return matcher.reset(item).matches();
        }
    }

    /**
     * A wrapper class for storing and working with the unprocessed values of a RegexResult.
     */
    public static class PathValueInfo {
        private static final Pattern QUOTES = Pattern.compile("\"([^\"]++)\"");
        private static final UnicodeSet SPACE_CHARACTERS = new UnicodeSet(
                "[\\u0000\\uFEFF[:pattern_whitespace:]]");

        private String rbPath;
        private String valueArg;
        private String groupKey;

        public PathValueInfo(String rbPath, String valueArg, String groupKey) {
            this.rbPath = rbPath;
            this.valueArg = valueArg;
            this.groupKey = groupKey;
        }

        public static PathValueInfo make(String rbPath, String valueArg, String groupKey) {
            return new PathValueInfo(rbPath, valueArg, groupKey);
        }

        /**
         * @param arguments the arguments retrieved from the regex corresponding to this PathValueInfo
         * @return the processed rb path
         */
        public String processRbPath(String[] arguments) {
            String path = processString(rbPath, arguments);
            // Replace slashes in metazone names,
            // e.g. "America/Argentina/La_Rioja"
            Matcher matcher = QUOTES.matcher(path);
            if (matcher.find()) {
                path = path.substring(0, matcher.start(1))
                    + matcher.group(1).replace('/', ':')
                    + path.substring(matcher.end(1)); 
            }
            return path;
        }
        
        public String processGroupKey(String[] arguments) {
            return processString(groupKey, arguments);
        }
        
        public List<String> processValues(String[] arguments, CLDRFile cldrFile,
                String xpath) {
            if (valueArg == null) {
                List<String> values = new ArrayList<String>();
                values.add(getStringValue(cldrFile, xpath));
                return values;
            }
            // Split single args using spaces.
            String processedValue = processValue(valueArg, xpath, cldrFile, arguments);
            return splitValues(processedValue);
        }
        
        private List<String> splitValues(String unsplitValues) {
            // Split up values using spaces unless enclosed by non-escaped quotes,
            // e.g. a "b \" c" would become {"a", "b \" c"}
            List<String> args = new ArrayList<String>();
            StringBuffer valueBuffer = new StringBuffer();
            boolean shouldSplit = true;
            char lastChar = ' ';
            for (char c : unsplitValues.toCharArray()) {
                // Normalize whitespace input.
                if (SPACE_CHARACTERS.contains(c)) {
                    c = ' ';
                }
                if (c == '"' && lastChar != '\\') {
                    shouldSplit = !shouldSplit;
                } else if (c == ' ') {
                    if (lastChar == ' ') {
                        // Do nothing.
                    } else if (shouldSplit) {
                        args.add(valueBuffer.toString());
                        valueBuffer.setLength(0);
                    } else {
                        valueBuffer.append(c);
                    }
                } else {
                    valueBuffer.append(c);
                }
                lastChar = c;
            }
            if (valueBuffer.length() > 0) {
                args.add(valueBuffer.toString());
            }
            return args;
        }

        private String processValue(String value, String xpath, CLDRFile cldrFile, String[] arguments) {
            value = processString(value, arguments);
            if (value.contains("{value}")) {
                value = value.replace("{value}", getStringValue(cldrFile, xpath));
            }
            return value;
        }
        
        @Override
        public String toString() { return rbPath + "=" + valueArg; }
        
        public String getGroupKey() { return groupKey; }

        @Override
        public boolean equals(Object o) {
            if (o instanceof PathValueInfo) {
                PathValueInfo otherInfo = (PathValueInfo)o;
                return rbPath.equals(otherInfo.rbPath)
                        && valueArg.equals(otherInfo.valueArg);
            } else {
                return false;
            }
        }
    }
    
    private static String processString(String value, String[] arguments) {
        if (value == null) {
            return null;
        }
        try {
            return RegexLookup.replace(value, arguments);
        } catch(ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException("Error while filling out arguments in " + value + " with " + Arrays.asList(arguments), e);
        }
    }

    static class PathValuePair {
        String path;
        List<String> values;
        String groupKey;
        
        public PathValuePair(String path, List<String> values, String groupKey) {
            // TODO: merge with CldrValue/IcuData
            this.path = path;
            this.values = values;
            this.groupKey = groupKey;
        }
    }

    private static class Argument {
        private int argNum;
        private boolean shouldSplit = true;
        public Argument(int argNum, boolean shouldSplit) {
            this.argNum = argNum;
            this.shouldSplit = shouldSplit;
        }
    }

    static class RegexResult implements Iterable<PathValueInfo> {
        private static final Pattern ARGUMENT_PATTERN = Pattern.compile("^\\$(\\d+)=(//.*)");
        // Matches arguments with or without enclosing quotes.
        private static final Pattern ARGUMENT = Pattern.compile("[<\"]?\\$(\\d)[\">]?");

        private Set<PathValueInfo> unprocessed;
        private Map<Integer, String> requiredArgs;
        private Argument[] rbArgs;

        public RegexResult(String rbPath, String rawValues,
                    Map<Integer, String> requiredArgs, Argument[] rbArgs,
                    String groupKey) {
            unprocessed = new HashSet<PathValueInfo>();
            unprocessed.add(PathValueInfo.make(rbPath, rawValues, groupKey));
            this.requiredArgs = requiredArgs;
            this.rbArgs = rbArgs;
        }

        /**
         * Merges this result with another RegexResult.
         * @param otherResult
         */
        public void merge(RegexResult otherResult) {
            for (PathValueInfo struct : otherResult.unprocessed) {
                unprocessed.add(struct);
            }
        }

        /**
         * Each RegexResult is only accessible if its corresponding regex
         * matched. However, there may be additional requirements imposed in order
         * for it to be a valid match, i.e. the arguments retrieved from the regex
         * must also match. This method checks that specified arguments match
         * the requirements for this RegexResult.
         * NOTE: LocaleMapper only
         * @param file
         * @param arguments
         * @return true if the arguments matched
         */
        public boolean argumentsMatch(CLDRFile file, String[] arguments) {
            for (int argNum : requiredArgs.keySet()) {
                if (arguments.length <= argNum) {
                    throw new IllegalArgumentException("Argument " + argNum + " missing");
                }
                String argFromCldr = getStringValue(file, requiredArgs.get(argNum));
                if (argFromCldr != null && !arguments[argNum].equals(argFromCldr)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Iterator<PathValueInfo> iterator() {
            return unprocessed.iterator();
        }

        // NOTE: only needed by SupplementalMapper
        public List<PathValuePair> processResult(CLDRFile cldrFile, String xpath,
                String[] arguments) {
            List<PathValuePair> processed = new ArrayList<PathValuePair>(unprocessed.size());
            for (PathValueInfo struct : unprocessed) {
                List<String> values = struct.processValues(arguments, cldrFile, xpath);
                // Check if there are any arguments that need splitting for the rbPath.
                String[] newArgs = arguments.clone();
                String groupKey = processString(struct.groupKey, newArgs);
                boolean splitNeeded = false;
                for (Argument arg : rbArgs) {
                    if (arg.shouldSplit) {
                        int argIndex = arg.argNum;
                        String[] splitArgs = arguments[argIndex].split("\\s++");
                        // Only split the first splittable argument needed for each rbPath.
                        if (splitArgs.length > 1) {
                            for (String splitArg : splitArgs) {
                                newArgs[argIndex] = splitArg;
                                String rbPath = struct.processRbPath(newArgs);
                                processed.add(new PathValuePair(rbPath, values, groupKey));
                            }
                            splitNeeded = true;
                            break;
                        }
                    }
                }
                // No splitting required, process as per normal.
                if (!splitNeeded) {
                    String rbPath = struct.processRbPath(arguments);
                    processed.add(new PathValuePair(rbPath, values, groupKey));
                }
             }
            return processed;
        }
    }

    /**
     * Wrapper class for the interim form of a CLDR value before it is added
     * into an IcuData object.
     */
    protected class CldrValue {
        private String xpath;
        private List<String> values;
        private String groupKey;

        public CldrValue(String xpath, List<String> values, String groupKey) {
            this.xpath = xpath;
            this.values = values;
            this.groupKey = groupKey;
        }

        public CldrValue(String xpath, String value, String groupKey) {
            this.xpath = xpath;
            this.values = new ArrayList<String>();
            values.add(value);
            this.groupKey = groupKey;
        }

        @Override
        public String toString() {
            return xpath + " " + values;
        }

        public List<String> getValues() { return values; }
        
        public String getXpath() { return xpath; }

        public String getGroupKey() { return groupKey; }
    }

    class CldrArray {
        private Map<String, R2<List<String>, String>> map;
        public CldrArray () {
            map = new HashMap<String, R2<List<String>, String>>();
        }
        
        public void add(String key, List<String> values, String groupKey) {
            R2<List<String>, String> existing = map.get(key);
            if (existing == null) {
                map.put(key, new R2<List<String>, String>(values, groupKey));
            } else {
                existing.get0().addAll(values);
            }
            
        }

        public void add(String key, String[] values, String groupKey) {
            List<String> list = new ArrayList<String>();
            for (String value : values) {
                list.add(value);
            }
            add(key, list, groupKey);
        }

        public void add(String key, String value, String groupKey) {
            List<String> list = new ArrayList<String>();
            list.add(value);
            add(key, list, groupKey);
        }

        public void addAll(CldrArray otherArray) {
            // HACK: narrow alias to abbreviated.
            for (String otherKey : otherArray.map.keySet()) {
                String narrowPath = otherKey.replace("eraAbbr", "eraNarrow");
                if (!map.containsKey(narrowPath)) {
                    map.put(narrowPath, otherArray.map.get(otherKey));
                }
               
            }
        }

        public boolean findKey(Finder finder) {
            for (String key : map.keySet()) {
                if (finder.find(key, null)) {
                    return true;
                }
            }
            return false;
        }
        public List<String[]> sortValues(Comparator<String> comparator) {
            List<String> sortedKeys = new ArrayList<String>(map.keySet());
            Collections.sort(sortedKeys, comparator);
            List<String[]> sortedValues = new ArrayList<String[]>();
            // Group isArray for the same xpath together.
            List<String> arrayValues = new ArrayList<String>();
            for (int i = 0, len = sortedKeys.size(); i < len; i++) {
                String key = sortedKeys.get(i);
                R2<List<String>, String> currentEntry = map.get(key);
                List<String> values = currentEntry.get0();
                String groupKey = currentEntry.get1();
                if (groupKey == null) {
                   for (String value : values) {
                       sortedValues.add(new String[] {value});
                   }
                } else {
                    arrayValues.addAll(values);
                    String nextKey = null;
                    if (i < len - 1) {
                        nextKey = map.get(sortedKeys.get(i + 1)).get1();
                    }
                    if (!groupKey.equals(nextKey)) {
                        sortedValues.add(toArray(arrayValues));
                        arrayValues.clear();
                    }
                }
            }
            return sortedValues;
        }
    }

    /**
     * Converts a list into an Array.
     */
    protected static String[] toArray(List<String> list) {
        String[] array = new String[list.size()];
        list.toArray(array);
        return array;
    }

    private static Merger<RegexResult> RegexValueMerger = new Merger<RegexResult>() {
        @Override
        public RegexResult merge(RegexResult a, RegexResult into) {
            into.merge(a);
            return into;
        }
    };

    private static Transform<String, Finder> regexTransform = new Transform<String, Finder>() {
        @Override
        public Finder transform(String source) {
            return new FullMatcher(source);
        }
    };

    /**
     * Checks if two strings match a specified pattern.
     * @param pattern the pattern to be matched against
     * @param arg0
     * @param arg1
     * @param matchers a 2-element array to contain the two matchers. The
     *        array is not guaranteed to be filled if matches() returns false
     * @return true if both strings successfully matched the pattern
     */
    protected static boolean matches(Pattern pattern, String arg0, String arg1, Matcher[] matchers) {
        return (matchers[0] = pattern.matcher(arg0)).matches()
                && (matchers[1] = pattern.matcher(arg1)).matches();
    }

    /**
     * @param cldrFile
     * @param xpath
     * @return the value of the specified xpath (fallback or otherwise)
     */
    protected static String getStringValue(CLDRFile cldrFile, String xpath) {
        String value = cldrFile.getStringValue(xpath);
        return value;
    }

    RegexLookup<FallbackInfo> fallbackConverter;
    RegexLookup<RegexResult> xpathConverter;

    // One FallbackInfo object for every type of rbPath.
    protected class FallbackInfo implements Iterable<R3<Finder, String, List<String>>> {
        private List<R3<Finder, String, List<String>>> fallbackItems;
        private List<Integer> argsUsed; // list of args used by the rb pattern
        private int numXpathArgs; // Number of args in the xpath

        public FallbackInfo(List<Integer> argsUsed, int numXpathArgs) {
            fallbackItems = new ArrayList<R3<Finder, String, List<String>>>();
            this.argsUsed = argsUsed;
            this.numXpathArgs = numXpathArgs;
        }
        
        public void addItem(Finder xpathMatcher, String fallbackXpath, String[] fallbackValues) {
            List<String> values = new ArrayList<String>();
            for (String fallbackValue : fallbackValues) {
                values.add(fallbackValue);
            }
            fallbackItems.add(new R3<Finder, String, List<String>>(xpathMatcher, fallbackXpath, values));
        }

        /**
         * Takes in arguments obtained from a RegexLookup on a RB path and fleshes
         * it out for the corresponding xpath.
         * @param arguments
         * @return
         */
        public String[] getArgumentsForXpath(String[] arguments) {
            String[] output = new String[numXpathArgs + 1];
            output[0] = arguments[0];
            for (int i = 0; i < argsUsed.size(); i++) {
                output[argsUsed.get(i)] = arguments[i + 1];
            }
            for (int i = 0; i < output.length; i++) {
                if (output[i] == null) output[i] = "x"; // dummy value
            }
            return output;
        }

        private R3<Finder, String, List<String>> getItem(Finder finder) {
            for (R3<Finder, String, List<String>> item : fallbackItems) {
                if (item.get0().equals(finder)) return item;
            }
            return null;
        }

        public FallbackInfo merge(FallbackInfo newInfo) {
            for (R3<Finder, String, List<String>> newItem : newInfo.fallbackItems) {
                R3<Finder, String, List<String>> item = getItem(newItem.get0());
                if (item == null) {
                    fallbackItems.add(newItem);
                } else {
                    item.get2().addAll(newItem.get2());
                }
            }
            return this;
        }

        @Override
        public Iterator<R3<Finder, String, List<String>>> iterator() {
            return fallbackItems.iterator();
        }
    }

    /**
     * All child classes should call this constructor.
     * @param converterFile
     */
    protected LdmlMapper(String converterFile) {
        this.converterFile = converterFile;
    }

    /**
     * @return a RegexLookup for matching rb paths that may require fallback
     * values.
     */
    protected RegexLookup<FallbackInfo> getFallbackConverter() {
        if (fallbackConverter == null) {
            loadConverters();
        }
        return fallbackConverter;
    }
 
    /**
     * @return a RegexLookup for matching xpaths
     */
    protected RegexLookup<RegexResult> getPathConverter() {
        if (xpathConverter == null) {
            loadConverters();
        }
        return xpathConverter;
    }
    
    private void loadConverters() {
        xpathConverter = new RegexLookup<RegexResult>()
            .setValueMerger(RegexValueMerger)
            .setPatternTransform(regexTransform);
        fallbackConverter = new RegexLookup<FallbackInfo>()
            .setValueMerger(new Merger<FallbackInfo>() {
                @Override
                public FallbackInfo merge(FallbackInfo a, FallbackInfo into) {
                    return into.merge(a);
                }
            });
        BufferedReader reader = FileUtilities.openFile(NewLdml2IcuConverter.class, converterFile);
        VariableReplacer variables = new VariableReplacer();
        String line;
        int lineNum = 0;
        try {
            while((line = reader.readLine()) != null) {
                lineNum++;
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                // Read variables.
                if (line.charAt(0) == '%') {
                    int pos = line.indexOf("=");
                    if (pos < 0) {
                        throw new IllegalArgumentException(
                            "Failed to read RegexLookup File " + converterFile +
                            "\t\t(" + lineNum + ") " + line);
                    }
                    variables.add(line.substring(0,pos).trim(), line.substring(pos+1).trim());
                    continue;
                }
                if (line.contains("%")) {
                    line = variables.replace(line);
                }
                processLine(line);
            }
        } catch(IOException e) {
            System.err.println("Error reading " + converterFile + " at line " + lineNum);
            e.printStackTrace();
        }
    }
    
    /**
     * Processes a line in the input file for xpath conversion.
     * @param line
     */
    private void processLine(String line) {
        String[] content = line.split(SEMI.toString());
        // xpath ; rbPath ; value
        // Create a reverse lookup for rbPaths to xpaths.
        Finder xpathMatcher = new FullMatcher(content[0].replace("[@", "\\[@"));
        String rbPath = content[1];
        
        // Find arguments in rbPath.
        Matcher matcher = RegexResult.ARGUMENT.matcher(rbPath);
        List<Argument> argList = new ArrayList<Argument>();
        while (matcher.find()) {
            char startChar = rbPath.charAt(matcher.start());
            char endChar = rbPath.charAt(matcher.end() - 1);
            boolean shouldSplit = !(startChar == '"' && endChar == '"' ||
                                    startChar == '<' && endChar == '>');
            argList.add(new Argument(Integer.parseInt(matcher.group(1)), shouldSplit));
        }
        Argument[] rbArgs = new Argument[argList.size()];
        argList.toArray(rbArgs);

        // Parse special instructions.
        String value = null;
        Map<Integer, String> requiredArgs = new HashMap<Integer, String>();
        String groupKey = null;
        for (int i = 2; i < content.length; i++) {
            String instruction = content[i];
            Matcher argMatcher;
            if (instruction.startsWith("values=")) {
                value = instruction.substring(7);
            } else if (instruction.startsWith("group=")) {
                groupKey = instruction.substring(6);
            } else if (instruction.startsWith("fallback=")) {
                // WARNING: fallback might backfire if more than one type of xpath for the same rbpath
                String fallbackValue = instruction.substring(9);
                addFallback(xpathMatcher, rbPath, fallbackValue);
            } else if ((argMatcher = RegexResult.ARGUMENT_PATTERN.matcher(instruction)).matches()) {
                requiredArgs.put(Integer.parseInt(argMatcher.group(1)),
                        argMatcher.group(2));
            }
        }
        xpathConverter.add(xpathMatcher, new RegexResult(rbPath, value, requiredArgs, rbArgs, groupKey));
    }

    /**
     * Adds an entry to the fallback converter.
     * @param xpathMatcher the xpath matcher that determines if a fallback value
     *        is necessary
     * @param rbPath the rb path that the fallback value is for
     * @param fallbackValue the fallback value
     */
    private void addFallback(Finder xpathMatcher, String rbPath, String fallbackValue) {
        ArrayList<StringBuffer> args = new ArrayList<StringBuffer>();
        int numBraces = 0;
        int argNum = 0;
        // Create RB path matcher and xpath replacement.
        // WARNING: doesn't currently take lookaround groups into account.
        StringBuffer xpathReplacement = new StringBuffer();
        for (char c : xpathMatcher.toString().toCharArray()) {
            boolean isBrace = false;
            if (c == '(') {
                numBraces++;
                argNum++;
                args.add(new StringBuffer());
                isBrace = true;
                if (numBraces == 1) {
                    xpathReplacement.append('$').append(argNum);
                }
            }
            if (numBraces > 0) {
                for (int i = args.size() - numBraces; i < args.size(); i++) {
                    args.get(i).append(c);
                }
            }
            if (c == ')') {
                numBraces--;
                isBrace = true;
            }
            if (!isBrace && numBraces == 0) {
                xpathReplacement.append(c);
            }
        }
        String fallbackXpath = xpathReplacement.toString().replaceAll("\\\\\\[", "[");
        if (fallbackXpath.contains("(")) {
            System.err.println("Warning: malformed xpath " + fallbackXpath);
        }

        // Create rb matcher.
        Matcher matcher = RegexResult.ARGUMENT.matcher(rbPath);
        StringBuffer rbPattern = new StringBuffer();
        int lastIndex = 0;
        List<Integer> argsUsed = new ArrayList<Integer>();
        while (matcher.find()) {
            rbPattern.append(rbPath.substring(lastIndex, matcher.start()));
            argNum = Integer.parseInt(matcher.group(1));
            rbPattern.append(args.get(argNum - 1));
            argsUsed.add(argNum);
            lastIndex = matcher.end();
        }
        rbPattern.append(rbPath.substring(lastIndex));
        FallbackInfo info = new FallbackInfo(argsUsed, args.size());
        info.addItem(xpathMatcher, fallbackXpath, fallbackValue.split("\\s"));
        fallbackConverter.add(rbPattern.toString(), info);
    }

    /**
     * Checks if any fallback values are required and adds them to the specified
     * map.
     * @param pathValueMap
     */
    protected void addFallbackValues(Map<String, CldrArray> pathValueMap) {
        RegexLookup<FallbackInfo> fallbackConverter = getFallbackConverter();
        for (String rbPath : pathValueMap.keySet()) {
            Output<String[]> arguments = new Output<String[]>();
            FallbackInfo fallbackInfo = fallbackConverter.get(rbPath, null, arguments);
            if (fallbackInfo == null) continue;
            CldrArray values = pathValueMap.get(rbPath);
            for (R3<Finder, String, List<String>> info : fallbackInfo) {
                if (!values.findKey(info.get0())) {
                    // The fallback xpath is just for sorting purposes.
                    String fallbackXpath = processString(info.get1(), fallbackInfo.getArgumentsForXpath(arguments.value));
                    // Sanity check.
                    if (fallbackXpath.contains("$")) {
                        System.err.println("Warning: " + fallbackXpath + " for " + rbPath +
                            " still contains unreplaced arguments.");
                    }
                    List<String> fallbackValues = info.get2();
                    List<String> valueList = new ArrayList<String>();
                    for (String value : fallbackValues) {
                        valueList.add(processString(value, arguments.value));
                    }
                    values.add(fallbackXpath, valueList, null);
                }
            }
        }
    }
}
