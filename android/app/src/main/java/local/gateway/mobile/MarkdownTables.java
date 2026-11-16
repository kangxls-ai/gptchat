package local.gateway.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MarkdownTables {
    enum Alignment { LEFT, CENTER, RIGHT }

    interface Block {}

    static final class Text implements Block {
        final String value;

        Text(String value) { this.value = value; }
    }

    static final class Table implements Block {
        final List<Alignment> alignments;
        final List<List<String>> rows = new ArrayList<>();

        Table(List<String> header, List<Alignment> alignments) {
            this.alignments = alignments;
            rows.add(header);
        }
    }

    private MarkdownTables() {}

    static List<Block> parse(String message) {
        String[] lines = message.split("\\r\\n|\\r|\\n", -1);
        List<Block> blocks = new ArrayList<>();
        int textStart = 0;
        String fence = "";
        int line = 0;
        while (line + 1 < lines.length) {
            String trimmed = lines[line].trim();
            if (!fence.isEmpty()) {
                if (trimmed.startsWith(fence)
                        && trimmed.substring(fence.length()).matches(
                                (fence.charAt(0) == '`' ? "`" : "~") + "*\\s*")) {
                    fence = "";
                }
            } else if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                char marker = trimmed.charAt(0);
                int end = 3;
                while (end < trimmed.length() && trimmed.charAt(end) == marker) end++;
                fence = trimmed.substring(0, end);
            } else {
                List<String> header = cells(lines[line]);
                List<String> next = cells(lines[line + 1]);
                List<Alignment> alignments = alignments(next);
                boolean hasRule = !header.isEmpty() && header.size() == alignments.size();
                boolean hasFramedRows = header.size() > 1 && header.size() == next.size()
                        && framedRow(lines[line]) && framedRow(lines[line + 1]) && alignments.isEmpty();
                if (hasRule || hasFramedRows) {
                    appendText(blocks, lines, textStart, line);
                    if (!hasRule) alignments = Collections.nCopies(header.size(), Alignment.LEFT);
                    Table table = new Table(header, alignments);
                    blocks.add(table);
                    line += hasRule ? 2 : 1;
                    while (line < lines.length) {
                        String row = lines[line].trim();
                        if (row.matches("(?:#{1,6}\\s|>|[-+*]\\s|\\d+[.)]\\s|```|~~~).*")) break;
                        List<String> values = cells(lines[line]);
                        if (values.isEmpty() || !hasRule && (!framedRow(row) || values.size() != header.size())) break;
                        List<String> fitted = new ArrayList<>(header.size());
                        for (int column = 0; column < header.size(); column++) {
                            fitted.add(column < values.size() ? values.get(column) : "");
                        }
                        table.rows.add(fitted);
                        line++;
                    }
                    textStart = line;
                    continue;
                }
            }
            line++;
        }
        if (blocks.isEmpty()) return Collections.singletonList(new Text(message));
        appendText(blocks, lines, textStart, lines.length);
        return blocks;
    }

    private static boolean framedRow(String line) {
        String text = line.trim();
        return text.startsWith("|") && text.endsWith("|");
    }

    private static void appendText(List<Block> blocks, String[] lines, int from, int to) {
        while (from < to && lines[from].trim().isEmpty()) from++;
        while (from < to && lines[to - 1].trim().isEmpty()) to--;
        if (from < to) blocks.add(new Text(String.join("\n", java.util.Arrays.copyOfRange(lines, from, to))));
    }

    private static List<Alignment> alignments(List<String> cells) {
        List<Alignment> result = new ArrayList<>();
        for (String cell : cells) {
            if (!cell.matches(":?-+:?")) return Collections.emptyList();
            if (!cell.endsWith(":")) result.add(Alignment.LEFT);
            else result.add(cell.startsWith(":") ? Alignment.CENTER : Alignment.RIGHT);
        }
        return result;
    }

    private static List<String> cells(String line) {
        String text = line.trim();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean separator = false;
        boolean endsWithSeparator = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            endsWithSeparator = character == '|';
            if (character == '\\' && index + 1 < text.length()) {
                char next = text.charAt(index + 1);
                if (next == '|' || next == '\\') {
                    cell.append(next);
                    index++;
                    continue;
                }
            }
            if (character == '|') {
                separator = true;
                if (index != 0) cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(character);
            }
        }
        if (!separator) return Collections.emptyList();
        if (!endsWithSeparator) cells.add(cell.toString().trim());
        return cells;
    }
}
