package dappbench;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.*;

public class Reporting {

    private Data data;

    public Reporting(Data data) {
        this.data = data;
    }

    public void process(String htmlFilePath) throws IOException {

        MustacheFactory mustacheFactory = new DefaultMustacheFactory();
        Mustache mustache = mustacheFactory.compile("report-template.html");
        StringWriter writer = new StringWriter();

        Map<String, Object> reportData = new HashMap<>();
        reportData.put("appSummary", data.getAppSummary());
        reportData.put("dockerInfo", data.getDockerInfo());
        reportData.put("filePath", data.getConfigFilePath());
        reportData.put("basicInformation", data.getBasicInformation());

        mustache.execute(writer, reportData).flush();
        String html = writer.toString();

        String timestamp = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
        PrintWriter reportFile = new PrintWriter(htmlFilePath + "/report-" + timestamp + ".html");
        reportFile.println(html);
        reportFile.close();
    }

}
