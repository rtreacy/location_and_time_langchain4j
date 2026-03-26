package edu.harvard.iq.datacommons.analyzer;

import dev.langchain4j.model.ollama.OllamaChatModel;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AnalyzerService {

    private final OllamaChatModel chatModel;
    private final String searchRoot;

    public AnalyzerService(OllamaChatModel chatModel, String searchRoot) {
        this.chatModel = chatModel;
        this.searchRoot = searchRoot;
    }

    public void run() throws Exception {
        Path root = Paths.get(searchRoot);
        System.out.println("Scanning directory: " + root.toAbsolutePath());

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> dataFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".csv") || path.toString().endsWith(".tab"))
                    .collect(Collectors.toList());

            for (Path file : dataFiles) {
                analyzeFile(file);
            }
        }
    }

    private void analyzeFile(Path file) {
        System.out.println("\nAnalyzing file: " + file);
        try {
            List<String> headers = new ArrayList<>();
            List<List<String>> samples = new ArrayList<>();

            CSVFormat format = CSVFormat.DEFAULT;
            if (file.toString().endsWith(".tab") || file.toString().endsWith(".tab.csv")) {
                format = CSVFormat.TDF;
            }

            try (Reader reader = new FileReader(file.toFile());
                 CSVParser csvParser = new CSVParser(reader, format.withFirstRecordAsHeader())) {
                
                headers = csvParser.getHeaderNames();
                int count = 0;
                for (CSVRecord record : csvParser) {
                    if (count >= 5) break;
                    List<String> row = new ArrayList<>();
                    for (String header : headers) {
                        row.add(record.get(header));
                    }
                    samples.add(row);
                    count++;
                }
            }

            if (headers.isEmpty()) {
                System.out.println("No headers found in " + file);
                return;
            }

            boolean hasLocation = false;
            boolean hasTime = false;

            for (int i = 0; i < headers.size(); i++) {
                String label = headers.get(i);
                final int index = i;
                List<String> values = samples.stream()
                        .filter(row -> index < row.size())
                        .map(row -> row.get(index))
                        .collect(Collectors.toList());

                if (values.isEmpty()) continue;

                if (!hasLocation && isLocation(label, values)) {
                    hasLocation = true;
                    System.out.println("Found location variable: " + label);
                }
                if (!hasTime && isTime(label, values)) {
                    hasTime = true;
                    System.out.println("Found time/date variable: " + label);
                }
            }

            if (hasLocation && hasTime) {
                System.out.println("RESULT: File " + file.getFileName() + " meets requirements (Has Location AND Time).");
            } else {
                System.out.println("RESULT: File " + file.getFileName() + " DOES NOT meet requirements.");
                if (!hasLocation) System.out.println(" - Missing Location");
                if (!hasTime) System.out.println(" - Missing Time/Date");
            }

        } catch (IOException e) {
            System.err.println("Error reading file " + file + ": " + e.getMessage());
        }
    }

    private boolean isLocation(String label, List<String> values) {
        String prompt = String.format(
                "Is the following variable a location (e.g., city, country, coordinates, address, latitude, longitude)?\n" +
                "Variable Label: %s\n" +
                "Sample Values: %s\n" +
                "Think carefully. If it's a coordinate like latitude or longitude, or a name of a geographical entity, it IS a location.\n" +
                "Respond with only 'YES' or 'NO'. Do not explain.",
                label, values.toString());
        String response = chatModel.generate(prompt);
        return response.trim().toUpperCase().startsWith("YES");
    }

    private boolean isTime(String label, List<String> values) {
        String prompt = String.format(
                "Is the following variable a time or date (e.g., year, month, timestamp, date, '2023-01-01', '12:00')?\n" +
                "Variable Label: %s\n" +
                "Sample Values: %s\n" +
                "Think carefully. If the label is 'date', 'year', 'year' (as a number), 'time', or similar, or if it represents a specific point in time, a duration, or a calendar value, it IS a time/date variable.\n" +
                "Respond with only 'YES' or 'NO'. Do not explain.",
                label, values.toString());
        String response = chatModel.generate(prompt);
        return response.trim().toUpperCase().startsWith("YES");
    }
}
