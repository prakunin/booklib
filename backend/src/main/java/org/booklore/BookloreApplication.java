package org.booklore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import org.booklore.config.AppProperties;
import org.booklore.config.BookmarkProperties;
import org.booklore.config.ImageIoConfig;

@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, BookmarkProperties.class})
@SpringBootApplication
public class BookloreApplication {

    public static void main(String[] args) {
        // Before the context, not inside it: startup migrations decode images, and bean init order
        // would not guarantee this ran first. See ImageIoConfig for why it matters.
        ImageIoConfig.applyDiskCacheSetting();
        SpringApplication.run(BookloreApplication.class, args);
    }
}
