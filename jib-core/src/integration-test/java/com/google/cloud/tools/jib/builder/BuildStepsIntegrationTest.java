/*
 * Copyright 2018 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.tools.jib.builder;

import com.google.cloud.tools.jib.Command;
import com.google.cloud.tools.jib.cache.CacheDirectoryCreationException;
import com.google.cloud.tools.jib.cache.CacheDirectoryNotOwnedException;
import com.google.cloud.tools.jib.cache.CacheMetadataCorruptedException;
import com.google.cloud.tools.jib.cache.Caches;
import com.google.cloud.tools.jib.frontend.ExposedPortsParser;
import com.google.cloud.tools.jib.image.ImageReference;
import com.google.cloud.tools.jib.registry.LocalRegistry;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Integration tests for {@link BuildSteps}. */
public class BuildStepsIntegrationTest {

  @ClassRule public static LocalRegistry localRegistry = new LocalRegistry(5000);

  private static final TestBuildLogger logger = new TestBuildLogger();

  @Rule public TemporaryFolder temporaryCacheDirectory = new TemporaryFolder();

  @Test
  public void testSteps_forBuildToDockerRegistry()
      throws IOException, URISyntaxException, InterruptedException, CacheMetadataCorruptedException,
          ExecutionException, CacheDirectoryNotOwnedException, CacheDirectoryCreationException {
    SourceFilesConfiguration sourceFilesConfiguration = new TestSourceFilesConfiguration();
    BuildConfiguration buildConfiguration =
        BuildConfiguration.builder(logger)
            .setBaseImage(ImageReference.of("gcr.io", "distroless/java", "latest"))
            .setTargetImage(ImageReference.of("localhost:5000", "testimage", "testtag"))
            .setMainClass("HelloWorld")
            .setJavaArguments(Collections.singletonList("An argument."))
            .setExposedPorts(
                ExposedPortsParser.parse(Arrays.asList("1000", "2000-2002/tcp", "3000/udp")))
            .setAllowHttp(true)
            .build();

    Path cacheDirectory = temporaryCacheDirectory.newFolder().toPath();
    BuildSteps buildImageSteps =
        BuildSteps.forBuildToDockerRegistry(
            buildConfiguration,
            sourceFilesConfiguration,
            new Caches.Initializer(cacheDirectory, false, cacheDirectory, false));

    long lastTime = System.nanoTime();
    buildImageSteps.run();
    logger.info("Initial build time: " + ((System.nanoTime() - lastTime) / 1_000_000));
    lastTime = System.nanoTime();
    buildImageSteps.run();
    logger.info("Secondary build time: " + ((System.nanoTime() - lastTime) / 1_000_000));

    String imageReference = "localhost:5000/testimage:testtag";
    new Command("docker", "pull", imageReference).run();
    Assert.assertThat(
        new Command("docker", "inspect", imageReference).run(),
        CoreMatchers.containsString(
            "            \"ExposedPorts\": {\n"
                + "                \"1000/tcp\": {},\n"
                + "                \"2000/tcp\": {},\n"
                + "                \"2001/tcp\": {},\n"
                + "                \"2002/tcp\": {},\n"
                + "                \"3000/udp\": {}"));
    Assert.assertEquals(
        "Hello, world. An argument.\n", new Command("docker", "run", imageReference).run());
  }

  @Test
  public void testSteps_forBuildToDockerDaemon()
      throws IOException, URISyntaxException, InterruptedException, CacheMetadataCorruptedException,
          ExecutionException, CacheDirectoryNotOwnedException, CacheDirectoryCreationException {
    SourceFilesConfiguration sourceFilesConfiguration = new TestSourceFilesConfiguration();
    BuildConfiguration buildConfiguration =
        BuildConfiguration.builder(logger)
            .setBaseImage(ImageReference.of("gcr.io", "distroless/java", "latest"))
            .setTargetImage(ImageReference.of(null, "testdocker", null))
            .setMainClass("HelloWorld")
            .setJavaArguments(Collections.singletonList("An argument."))
            .setExposedPorts(
                ExposedPortsParser.parse(Arrays.asList("1000", "2000-2002/tcp", "3000/udp")))
            .build();

    Path cacheDirectory = temporaryCacheDirectory.newFolder().toPath();
    BuildSteps buildDockerSteps =
        BuildSteps.forBuildToDockerDaemon(
            buildConfiguration,
            sourceFilesConfiguration,
            new Caches.Initializer(cacheDirectory, false, cacheDirectory, false));

    buildDockerSteps.run();
    Assert.assertThat(
        new Command("docker", "inspect", "testdocker").run(),
        CoreMatchers.containsString(
            "            \"ExposedPorts\": {\n"
                + "                \"1000/tcp\": {},\n"
                + "                \"2000/tcp\": {},\n"
                + "                \"2001/tcp\": {},\n"
                + "                \"2002/tcp\": {},\n"
                + "                \"3000/udp\": {}"));
    Assert.assertEquals(
        "Hello, world. An argument.\n", new Command("docker", "run", "testdocker").run());
  }
}
