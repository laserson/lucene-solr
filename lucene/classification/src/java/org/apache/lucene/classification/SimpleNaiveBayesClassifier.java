package org.apache.lucene.classification;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.LinkedList;

/**
 * A simplistic Lucene based NaiveBayes classifier, see <code>http://en.wikipedia.org/wiki/Naive_Bayes_classifier</code>
 */
public class SimpleNaiveBayesClassifier implements Classifier {

  private AtomicReader atomicReader;
  private String textFieldName;
  private String classFieldName;
  private int docsWithClassSize;
  private Analyzer analyzer;
  private IndexSearcher indexSearcher;

  public void train(AtomicReader atomicReader, String textFieldName, String classFieldName, Analyzer analyzer)
      throws IOException {
    this.atomicReader = atomicReader;
    this.indexSearcher = new IndexSearcher(this.atomicReader);
    this.textFieldName = textFieldName;
    this.classFieldName = classFieldName;
    this.analyzer = analyzer;
    this.docsWithClassSize = MultiFields.getTerms(this.atomicReader, this.classFieldName).getDocCount();
  }

  private String[] tokenizeDoc(String doc) throws IOException {
    Collection<String> result = new LinkedList<String>();
    TokenStream tokenStream = analyzer.tokenStream(textFieldName, new StringReader(doc));
    CharTermAttribute charTermAttribute = tokenStream.addAttribute(CharTermAttribute.class);
    tokenStream.reset();
    while (tokenStream.incrementToken()) {
      result.add(charTermAttribute.toString());
    }
    tokenStream.end();
    tokenStream.close();
    return result.toArray(new String[result.size()]);
  }

  public String assignClass(String inputDocument) throws IOException {
    if (atomicReader == null) {
      throw new RuntimeException("need to train the classifier first");
    }
    Double max = 0d;
    String foundClass = null;

    Terms terms = MultiFields.getTerms(atomicReader, classFieldName);
    TermsEnum termsEnum = terms.iterator(null);
    BytesRef t = termsEnum.next();
    while (t != null) {
      String classValue = t.utf8ToString();
      // TODO : turn it to be in log scale
      Double clVal = calculatePrior(classValue) * calculateLikelihood(inputDocument, classValue);
      if (clVal > max) {
        max = clVal;
        foundClass = classValue;
      }
      t = termsEnum.next();
    }
    return foundClass;
  }


  private Double calculateLikelihood(String document, String c) throws IOException {
    // for each word
    Double result = 1d;
    for (String word : tokenizeDoc(document)) {
      // search with text:word AND class:c
      int hits = getWordFreqForClass(word, c);

      // num : count the no of times the word appears in documents of class c (+1)
      double num = hits + 1; // +1 is added because of add 1 smoothing

      // den : for the whole dictionary, count the no of times a word appears in documents of class c (+|V|)
      double den = getTextTermFreqForClass(c) + docsWithClassSize;

      // P(w|c) = num/den
      double wordProbability = num / den;
      result *= wordProbability;
    }

    // P(d|c) = P(w1|c)*...*P(wn|c)
    return result;
  }

  private double getTextTermFreqForClass(String c) throws IOException {
    Terms terms = MultiFields.getTerms(atomicReader, textFieldName);
    long numPostings = terms.getSumDocFreq(); // number of term/doc pairs
    double avgNumberOfUniqueTerms = numPostings / (double) terms.getDocCount(); // avg # of unique terms per doc
    int docsWithC = atomicReader.docFreq(classFieldName, new BytesRef(c));
    return avgNumberOfUniqueTerms * docsWithC; // avg # of unique terms in text field per doc * # docs with c
  }

  private int getWordFreqForClass(String word, String c) throws IOException {
    BooleanQuery booleanQuery = new BooleanQuery();
    booleanQuery.add(new BooleanClause(new TermQuery(new Term(textFieldName, word)), BooleanClause.Occur.MUST));
    booleanQuery.add(new BooleanClause(new TermQuery(new Term(classFieldName, c)), BooleanClause.Occur.MUST));
    return indexSearcher.search(booleanQuery, 1).totalHits;
  }

  private Double calculatePrior(String currentClass) throws IOException {
    return (double) docCount(currentClass) / docsWithClassSize;
  }

  private int docCount(String countedClass) throws IOException {
    return atomicReader.docFreq(new Term(classFieldName, countedClass));
  }
}