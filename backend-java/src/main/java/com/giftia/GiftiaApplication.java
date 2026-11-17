package com.giftia;

import com.giftia.config.DotenvLoader;
import com.giftia.config.WorkspacePaths;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GiftiaApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GiftiaApplication.class);
        application.setDefaultProperties(DotenvLoader.read(WorkspacePaths.root().resolve(".env")));
        application.run(args);
    }
}
