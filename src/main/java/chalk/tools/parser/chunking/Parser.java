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


package chalk.tools.parser.chunking;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nak.model.AbstractModel;
import nak.model.MaxentModel;
import nak.model.TrainUtil;
import nak.model.TwoPassDataIndexer;
import chalk.tools.chunker.Chunker;
import chalk.tools.chunker.ChunkerME;
import chalk.tools.chunker.ChunkerModel;
import chalk.tools.dictionary.Dictionary;
import chalk.tools.namefind.TokenNameFinderEvaluationMonitor;
import chalk.tools.parser.AbstractBottomUpParser;
import chalk.tools.parser.ChunkContextGenerator;
import chalk.tools.parser.ChunkSampleStream;
import chalk.tools.parser.HeadRules;
import chalk.tools.parser.Parse;
import chalk.tools.parser.ParserChunkerSequenceValidator;
import chalk.tools.parser.ParserEventTypeEnum;
import chalk.tools.parser.ParserModel;
import chalk.tools.parser.ParserType;
import chalk.tools.parser.PosSampleStream;
import chalk.tools.postag.POSModel;
import chalk.tools.postag.POSTagger;
import chalk.tools.postag.POSTaggerME;
import chalk.tools.util.ObjectStream;
import chalk.tools.util.Span;
import chalk.tools.util.TrainingParameters;


/**
 * Class for a shift reduce style parser based on Adwait Ratnaparkhi's 1998 thesis.
 */
public class Parser extends AbstractBottomUpParser {

  private MaxentModel buildModel;
  private MaxentModel checkModel;

  private BuildContextGenerator buildContextGenerator;
  private CheckContextGenerator checkContextGenerator;

  private double[] bprobs;
  private double[] cprobs;

  private static final String TOP_START = START + TOP_NODE;
  private int topStartIndex;
  private Map<String, String> startTypeMap;
  private Map<String, String> contTypeMap;

  private int completeIndex;
  private int incompleteIndex;

  public Parser(ParserModel model, int beamSize, double advancePercentage) {
    this(model.getBuildModel(), model.getCheckModel(),
        new POSTaggerME(model.getParserTaggerModel(), 10, 0),
        new ChunkerME(model.getParserChunkerModel(),
            ChunkerME.DEFAULT_BEAM_SIZE,
            new ParserChunkerSequenceValidator(model.getParserChunkerModel()),
            new ChunkContextGenerator(ChunkerME.DEFAULT_BEAM_SIZE)),
            model.getHeadRules(), beamSize, advancePercentage);
  }
  
  public Parser(ParserModel model) {
    this(model, defaultBeamSize, defaultAdvancePercentage);
  }

  /**
   * Creates a new parser using the specified models and head rules.
   * @param buildModel The model to assign constituent labels.
   * @param checkModel The model to determine a constituent is complete.
   * @param tagger The model to assign pos-tags.
   * @param chunker The model to assign flat constituent labels.
   * @param headRules The head rules for head word perculation.
   */
  @Deprecated
  public Parser(MaxentModel buildModel, MaxentModel checkModel, POSTagger tagger, Chunker chunker, HeadRules headRules) {
  	this(buildModel,checkModel,tagger,chunker,headRules,defaultBeamSize,defaultAdvancePercentage);
  }

  /**
   * Creates a new parser using the specified models and head rules using the specified beam size and advance percentage.
   * @param buildModel The model to assign constituent labels.
   * @param checkModel The model to determine a constituent is complete.
   * @param tagger The model to assign pos-tags.
   * @param chunker The model to assign flat constituent labels.
   * @param headRules The head rules for head word perculation.
   * @param beamSize The number of different parses kept during parsing.
   * @param advancePercentage The minimal amount of probability mass which advanced outcomes must represent.
   * Only outcomes which contribute to the top "advancePercentage" will be explored.
   */
  @Deprecated
  public Parser(MaxentModel buildModel, MaxentModel checkModel, POSTagger tagger, Chunker chunker, HeadRules headRules, int beamSize, double advancePercentage) {
    super(tagger, chunker, headRules, beamSize, advancePercentage);
    this.buildModel = buildModel;
    this.checkModel = checkModel;
    bprobs = new double[buildModel.getNumOutcomes()];
    cprobs = new double[checkModel.getNumOutcomes()];
    this.buildContextGenerator = new BuildContextGenerator();
    this.checkContextGenerator = new CheckContextGenerator();
    startTypeMap = new HashMap<String, String>();
    contTypeMap = new HashMap<String, String>();
    for (int boi = 0, bon = buildModel.getNumOutcomes(); boi < bon; boi++) {
      String outcome = buildModel.getOutcome(boi);
      if (outcome.startsWith(START)) {
        //System.err.println("startMap "+outcome+"->"+outcome.substring(START.length()));
        startTypeMap.put(outcome, outcome.substring(START.length()));
      }
      else if (outcome.startsWith(CONT)) {
        //System.err.println("contMap "+outcome+"->"+outcome.substring(CONT.length()));
        contTypeMap.put(outcome, outcome.substring(CONT.length()));
      }
    }
    topStartIndex = buildModel.getIndex(TOP_START);
    completeIndex = checkModel.getIndex(COMPLETE);
    incompleteIndex = checkModel.getIndex(INCOMPLETE);
  }

  @Override
  protected void advanceTop(Parse p) {
    buildModel.eval(buildContextGenerator.getContext(p.getChildren(), 0), bprobs);
    p.addProb(Math.log(bprobs[topStartIndex]));
    checkModel.eval(checkContextGenerator.getContext(p.getChildren(), TOP_NODE, 0, 0), cprobs);
    p.addProb(Math.log(cprobs[completeIndex]));
    p.setType(TOP_NODE);
  }

  @Override
  protected Parse[] advanceParses(final Parse p, double probMass) {
    double q = 1 - probMass;
    /** The closest previous node which has been labeled as a start node. */
    Parse lastStartNode = null;
    /** The index of the closest previous node which has been labeled as a start node. */
    int lastStartIndex = -1;
    /** The type of the closest previous node which has been labeled as a start node. */
    String lastStartType = null;
    /** The index of the node which will be labeled in this iteration of advancing the parse. */
    int advanceNodeIndex;
    /** The node which will be labeled in this iteration of advancing the parse. */
    Parse advanceNode=null;
    Parse[] originalChildren = p.getChildren();
    Parse[] children = collapsePunctuation(originalChildren,punctSet);
    int numNodes = children.length;
    if (numNodes == 0) {
      return null;
    }
    //determines which node needs to be labeled and prior labels.
    for (advanceNodeIndex = 0; advanceNodeIndex < numNodes; advanceNodeIndex++) {
      advanceNode = children[advanceNodeIndex];
      if (advanceNode.getLabel() == null) {
        break;
      }
      else if (startTypeMap.containsKey(advanceNode.getLabel())) {
        lastStartType = startTypeMap.get(advanceNode.getLabel());
        lastStartNode = advanceNode;
        lastStartIndex = advanceNodeIndex;
        //System.err.println("lastStart "+i+" "+lastStart.label+" "+lastStart.prob);
      }
    }
    int originalAdvanceIndex = mapParseIndex(advanceNodeIndex,children,originalChildren);
    List<Parse> newParsesList = new ArrayList<Parse>(buildModel.getNumOutcomes());
    //call build
    buildModel.eval(buildContextGenerator.getContext(children, advanceNodeIndex), bprobs);
    double bprobSum = 0;
    while (bprobSum < probMass) {
      // The largest unadvanced labeling.
      int max = 0;
      for (int pi = 1; pi < bprobs.length; pi++) { //for each build outcome
        if (bprobs[pi] > bprobs[max]) {
          max = pi;
        }
      }
      if (bprobs[max] == 0) {
        break;
      }
      double bprob = bprobs[max];
      bprobs[max] = 0; //zero out so new max can be found
      bprobSum += bprob;
      String tag = buildModel.getOutcome(max);
      //System.out.println("trying "+tag+" "+bprobSum+" lst="+lst);
      if (max == topStartIndex) { // can't have top until complete
        continue;
      }
      //System.err.println(i+" "+tag+" "+bprob);
      if (startTypeMap.containsKey(tag)) { //update last start
        lastStartIndex = advanceNodeIndex;
        lastStartNode = advanceNode;
        lastStartType = startTypeMap.get(tag);
      }
      else if (contTypeMap.containsKey(tag)) {
        if (lastStartNode == null || !lastStartType.equals(contTypeMap.get(tag))) {
          continue; //Cont must match previous start or continue
        }
      }
      Parse newParse1 = (Parse) p.clone(); //clone parse
      if (createDerivationString) newParse1.getDerivation().append(max).append("-");
      newParse1.setChild(originalAdvanceIndex,tag); //replace constituent being labeled to create new derivation
      newParse1.addProb(Math.log(bprob));
      //check
      //String[] context = checkContextGenerator.getContext(newParse1.getChildren(), lastStartType, lastStartIndex, advanceNodeIndex);
      checkModel.eval(checkContextGenerator.getContext(collapsePunctuation(newParse1.getChildren(),punctSet), lastStartType, lastStartIndex, advanceNodeIndex), cprobs);
      //System.out.println("check "+lastStartType+" "+cprobs[completeIndex]+" "+cprobs[incompleteIndex]+" "+tag+" "+java.util.Arrays.asList(context));
      Parse newParse2 = newParse1;
      if (cprobs[completeIndex] > q) { //make sure a reduce is likely
        newParse2 = (Parse) newParse1.clone();
        if (createDerivationString) newParse2.getDerivation().append(1).append(".");
        newParse2.addProb(Math.log(cprobs[completeIndex]));
        Parse[] cons = new Parse[advanceNodeIndex - lastStartIndex + 1];
        boolean flat = true;
        //first
        cons[0] = lastStartNode;
        flat &= cons[0].isPosTag();
        //last
        cons[advanceNodeIndex - lastStartIndex] = advanceNode;
        flat &= cons[advanceNodeIndex - lastStartIndex].isPosTag();
        //middle
        for (int ci = 1; ci < advanceNodeIndex - lastStartIndex; ci++) {
          cons[ci] = children[ci + lastStartIndex];
          flat &= cons[ci].isPosTag();
        }
        if (!flat) { //flat chunks are done by chunker
          if (lastStartIndex == 0 && advanceNodeIndex == numNodes-1) { //check for top node to include end and begining punctuation
            //System.err.println("ParserME.advanceParses: reducing entire span: "+new Span(lastStartNode.getSpan().getStart(), advanceNode.getSpan().getEnd())+" "+lastStartType+" "+java.util.Arrays.asList(children));
            newParse2.insert(new Parse(p.getText(), p.getSpan(), lastStartType, cprobs[1], headRules.getHead(cons, lastStartType)));
          }
          else {
            newParse2.insert(new Parse(p.getText(), new Span(lastStartNode.getSpan().getStart(), advanceNode.getSpan().getEnd()), lastStartType, cprobs[1], headRules.getHead(cons, lastStartType)));
          }
          newParsesList.add(newParse2);
        }
      }
      if (cprobs[incompleteIndex] > q) { //make sure a shift is likely
        if (createDerivationString) newParse1.getDerivation().append(0).append(".");
        if (advanceNodeIndex != numNodes - 1) { //can't shift last element
          newParse1.addProb(Math.log(cprobs[incompleteIndex]));
          newParsesList.add(newParse1);
        }
      }
    }
    Parse[] newParses = new Parse[newParsesList.size()];
    newParsesList.toArray(newParses);
    return newParses;
  }

  /**
   * @deprecated Please do not use anymore, use the ObjectStream train methods instead! This method
   * will be removed soon.
   */
  @Deprecated
  public static AbstractModel train(nak.model.EventStream es, int iterations, int cut) throws java.io.IOException {
    return nak.maxent.GIS.trainModel(iterations, new TwoPassDataIndexer(es, cut));
  }

  public static void mergeReportIntoManifest(Map<String, String> manifest, 
      Map<String, String> report, String namespace) {
    
    for (Map.Entry<String, String> entry : report.entrySet()) {
      manifest.put(namespace + "." + entry.getKey(), entry.getValue());
    }
  }
  
  public static ParserModel train(String languageCode, ObjectStream<Parse> parseSamples, HeadRules rules, TrainingParameters mlParams)
          throws IOException {
    
    System.err.println("Building dictionary");
    
    Dictionary mdict = buildDictionary(parseSamples, rules, mlParams);
    
    parseSamples.reset();
    
    Map<String, String> manifestInfoEntries = new HashMap<String, String>();
    
    // build
    System.err.println("Training builder");
    nak.model.EventStream bes = new ParserEventStream(parseSamples, rules, ParserEventTypeEnum.BUILD, mdict);
    Map<String, String> buildReportMap = new HashMap<String, String>();
    AbstractModel buildModel = TrainUtil.train(bes, mlParams.getSettings("build"), buildReportMap);
    mergeReportIntoManifest(manifestInfoEntries, buildReportMap, "build");
    
    parseSamples.reset();
    
    // tag
    POSModel posModel = POSTaggerME.train(languageCode, new PosSampleStream(parseSamples), 
        mlParams.getParameters("tagger"), null, null);
    
    parseSamples.reset();
    
    // chunk
    ChunkerModel chunkModel = ChunkerME.train(languageCode, 
        new ChunkSampleStream(parseSamples),
        new ChunkContextGenerator(), mlParams.getParameters("chunker"));
    
    parseSamples.reset();
    
    // check
    System.err.println("Training checker");
    nak.model.EventStream kes = new ParserEventStream(parseSamples, rules, ParserEventTypeEnum.CHECK);
    Map<String, String> checkReportMap = new HashMap<String, String>();
    AbstractModel checkModel = TrainUtil.train(kes, mlParams.getSettings("check"), checkReportMap);
    mergeReportIntoManifest(manifestInfoEntries, checkReportMap, "check");

    // TODO: Remove cast for HeadRules
    return new ParserModel(languageCode, buildModel, checkModel,
        posModel, chunkModel, (chalk.tools.parser.lang.en.HeadRules) rules,
        ParserType.CHUNKING, manifestInfoEntries);
  }

  /**
  * @deprecated use {@link #train(String, ObjectStream, HeadRules, TrainingParameters)}
  * instead and pass in a TrainingParameters object.
  */
  @Deprecated
  public static ParserModel train(String languageCode, ObjectStream<Parse> parseSamples, HeadRules rules, int iterations, int cut)
      throws IOException {
    
    TrainingParameters params = new TrainingParameters();
    params.put("dict", TrainingParameters.CUTOFF_PARAM, Integer.toString(cut));

    params.put("tagger", TrainingParameters.CUTOFF_PARAM, Integer.toString(cut));
    params.put("tagger", TrainingParameters.ITERATIONS_PARAM, Integer.toString(iterations));
    params.put("chunker", TrainingParameters.CUTOFF_PARAM, Integer.toString(cut));
    params.put("chunker", TrainingParameters.ITERATIONS_PARAM, Integer.toString(iterations));
    params.put("check", TrainingParameters.CUTOFF_PARAM, Integer.toString(cut));
    params.put("check", TrainingParameters.ITERATIONS_PARAM, Integer.toString(iterations));
    params.put("build", TrainingParameters.CUTOFF_PARAM, Integer.toString(cut));
    params.put("build", TrainingParameters.ITERATIONS_PARAM, Integer.toString(iterations));

    return train(languageCode, parseSamples, rules, params);
  }
}
