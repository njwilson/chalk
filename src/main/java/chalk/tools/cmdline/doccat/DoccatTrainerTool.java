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

package chalk.tools.cmdline.doccat;

import java.io.File;
import java.io.IOException;

import chalk.tools.cmdline.AbstractTrainerTool;
import chalk.tools.cmdline.CmdLineUtil;
import chalk.tools.cmdline.TerminateToolException;
import chalk.tools.cmdline.doccat.DoccatTrainerTool.TrainerToolParams;
import chalk.tools.cmdline.params.TrainingToolParams;
import chalk.tools.doccat.DoccatModel;
import chalk.tools.doccat.DocumentCategorizerME;
import chalk.tools.doccat.DocumentSample;
import chalk.tools.util.model.ModelUtil;


public class DoccatTrainerTool
    extends AbstractTrainerTool<DocumentSample, TrainerToolParams> {
  
  interface TrainerToolParams extends TrainingParams, TrainingToolParams {
  }

  public DoccatTrainerTool() {
    super(DocumentSample.class, TrainerToolParams.class);
  }

  public String getShortDescription() {
    return "trainer for the learnable document categorizer";
  }
  
  public void run(String format, String[] args) {
    super.run(format, args);

    mlParams = CmdLineUtil.loadTrainingParameters(params.getParams(), false);
    if(mlParams == null) {
      mlParams = ModelUtil.createTrainingParameters(params.getIterations(), params.getCutoff());
    }

    File modelOutFile = params.getModel();

    CmdLineUtil.checkOutputFile("document categorizer model", modelOutFile);

    DoccatModel model;
    try {
      model = DocumentCategorizerME.train(factory.getLang(), sampleStream, mlParams);
    } catch (IOException e) {
      throw new TerminateToolException(-1, "IO error while reading training data or indexing data: " +
          e.getMessage(), e);
    }
    finally {
      try {
        sampleStream.close();
      } catch (IOException e) {
        // sorry that this can fail
      }
    }
    
    CmdLineUtil.writeModel("document categorizer", modelOutFile, model);
  }
}
