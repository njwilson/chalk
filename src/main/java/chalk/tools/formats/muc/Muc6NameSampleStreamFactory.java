/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package chalk.tools.formats.muc;

import java.io.File;
import java.io.FileFilter;
import java.nio.charset.Charset;

import chalk.tools.cmdline.ArgumentParser;
import chalk.tools.cmdline.StreamFactoryRegistry;
import chalk.tools.cmdline.ArgumentParser.ParameterDescription;
import chalk.tools.cmdline.params.LanguageFormatParams;
import chalk.tools.cmdline.tokenizer.TokenizerModelLoader;
import chalk.tools.formats.DirectorySampleStream;
import chalk.tools.formats.LanguageSampleStreamFactory;
import chalk.tools.formats.convert.FileToStringSampleStream;
import chalk.tools.namefind.NameSample;
import chalk.tools.tokenize.Tokenizer;
import chalk.tools.tokenize.TokenizerME;
import chalk.tools.tokenize.TokenizerModel;
import chalk.tools.util.ObjectStream;


public class Muc6NameSampleStreamFactory  extends LanguageSampleStreamFactory<NameSample> {

  interface Parameters extends LanguageFormatParams {
    @ParameterDescription(valueName = "modelFile")
    File getTokenizerModel();
  }

  protected Muc6NameSampleStreamFactory() {
    super(Parameters.class);
  }

  public ObjectStream<NameSample> create(String[] args) {

    Parameters params = ArgumentParser.parse(args, Parameters.class);

    language = params.getLang();

    TokenizerModel tokenizerModel = new TokenizerModelLoader().load(params.getTokenizerModel());
    Tokenizer tokenizer = new TokenizerME(tokenizerModel);

    ObjectStream<String> mucDocStream = new FileToStringSampleStream(
        new DirectorySampleStream(params.getData(), new FileFilter() {

          public boolean accept(File file) {
            return file.getName().toLowerCase().endsWith(".sgm");
          }
        }, false), Charset.forName("UTF-8"));

    return new MucNameSampleStream(tokenizer, mucDocStream);
  }

  public static void registerFactory() {
    StreamFactoryRegistry.registerFactory(NameSample.class, "muc6",
        new Muc6NameSampleStreamFactory());
  }
}
