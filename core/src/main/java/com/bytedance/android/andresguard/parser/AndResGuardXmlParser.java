package com.bytedance.android.andresguard.parser;

import com.bytedance.android.andresguard.model.xml.AndResGuardConfig;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import java.nio.file.Path;
import java.util.Iterator;

import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileExistsAndReadable;

/**
 * Created by YangJing on 2019/10/14 .
 * Email: yangjing.yeoh@bytedance.com
 */
public class AndResGuardXmlParser {
    private final Path configPath;

    public AndResGuardXmlParser(Path configPath) {
        checkFileExistsAndReadable(configPath);
        this.configPath = configPath;
    }

    public AndResGuardConfig parse() throws DocumentException {
        AndResGuardConfig andResGuardConfig = new AndResGuardConfig();
        SAXReader reader = SecureSaxReader.create();
        Document doc = reader.read(configPath.toFile());
        Element root = doc.getRootElement();
        for (Iterator i = root.elementIterator("issue"); i.hasNext(); ) {
            Element element = (Element) i.next();
            String id = element.attributeValue("id");
            if (id == null || !id.equals("whitelist")) {
                continue;
            }
            String isActive = element.attributeValue("isactive");
            if (isActive != null && isActive.equals("true")) {
                andResGuardConfig.setUseWhiteList(true);
            }
            for (Iterator rules = element.elementIterator("path"); rules.hasNext(); ) {
                Element ruleElement = (Element) rules.next();
                String rule = ruleElement.attributeValue("value");
                if (rule != null) {
                    andResGuardConfig.addWhiteList(rule);
                }
            }
        }

        // file filter
        andResGuardConfig.setFileFilter(new FileFilterXmlParser(configPath).parse());

        // string filter
        andResGuardConfig.setStringFilterConfig(new StringFilterXmlParser(configPath).parse());

        return andResGuardConfig;
    }
}
