/*
 * Copyright (c) 2017 Villu Ruusmann
 *
 * This file is part of JPMML-LightGBM
 *
 * JPMML-LightGBM is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-LightGBM is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-LightGBM.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.lightgbm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.Interval;
import org.dmg.pmml.InvalidValueTreatmentMethod;
import org.dmg.pmml.OpType;
import org.dmg.pmml.PMML;
import org.dmg.pmml.Visitor;
import org.dmg.pmml.mining.MiningModel;
import org.jpmml.converter.BinaryFeature;
import org.jpmml.converter.CategoricalFeature;
import org.jpmml.converter.ContinuousFeature;
import org.jpmml.converter.Feature;
import org.jpmml.converter.ImportanceDecorator;
import org.jpmml.converter.InvalidValueDecorator;
import org.jpmml.converter.Label;
import org.jpmml.converter.ModelEncoder;
import org.jpmml.converter.Schema;
import org.jpmml.converter.SchemaUtil;
import org.jpmml.converter.TypeUtil;
import org.jpmml.converter.WildcardFeature;
import org.jpmml.lightgbm.visitors.TreeModelCompactor;

public class GBDT {

	private String version;

	private int max_feature_idx_;

	private int label_idx_;

	private String[] feature_names_;

	private String[] feature_infos_;

	private boolean boost_from_average_;

	private ObjectiveFunction object_function_;

	private Tree[] models_;

	private Map<String, String> feature_importances = Collections.emptyMap();

	private List<List<String>> pandas_categorical = Collections.emptyList();


	public void load(List<Section> sections){
		int index = 0;

		if(true){
			Section section = sections.get(index);

			if(!section.checkId("tree")){
				throw new IllegalArgumentException();
			}

			this.version = section.getString("version");
			if(this.version != null){

				switch(this.version){
					case "v2":
					case "v3":
						break;
					default:
						throw new IllegalArgumentException("Version " + this.version + " is not supported");
				}
			}

			this.max_feature_idx_ = section.getInt("max_feature_idx");
			this.label_idx_ = section.getInt("label_index");
			this.feature_names_ = section.getStringArray("feature_names", this.max_feature_idx_ + 1);
			this.feature_infos_ = section.getStringArray("feature_infos", this.max_feature_idx_ + 1);
			this.boost_from_average_ = section.containsKey("boost_from_average");

			this.object_function_ = loadObjectiveFunction(section);

			index++;
		}

		List<Tree> trees = new ArrayList<>();

		while(index < sections.size()){
			Section section = sections.get(index);

			String treeId = "Tree=" + String.valueOf(index - 1);

			if(!section.checkId(treeId)){
				break;
			}

			Tree tree = new Tree();
			tree.load(section);

			trees.add(tree);

			index++;
		}

		this.models_ = trees.toArray(new Tree[trees.size()]);

		index = skipEndSection("end of trees", sections, index);

		feature_importances:
		if(index < sections.size()){
			Section section = sections.get(index);

			if(!section.checkId("feature importances:")){
				break feature_importances;
			}

			this.feature_importances = loadFeatureSection(section);

			index++;
		}

		parameters:
		if(index < sections.size()){
			Section section = sections.get(index);

			if(!section.checkId("parameters:")){
				break parameters;
			}

			index++;

			index = skipEndSection("end of parameters", sections, index);
		}

		pandas_categorical:
		if(index < sections.size()){
			Section section = sections.get(index);

			if(!section.checkId(id -> id.startsWith("pandas_categorical:"))){
				break pandas_categorical;
			}

			this.pandas_categorical = loadPandasCategorical(section);

			index++;
		}
	}

	public Schema encodeSchema(FieldName targetField, List<String> targetCategories, LightGBMEncoder encoder){
		Label label;

		{
			if(targetField == null){
				targetField = FieldName.create("_target");
			}

			label = this.object_function_.encodeLabel(targetField, targetCategories, encoder);
		}

		List<Feature> features = new ArrayList<>();

		boolean hasPandasCategories = (this.pandas_categorical.size() > 0);

		int pandasCategoryIndex = 0;

		String[] featureNames = this.feature_names_;
		String[] featureInfos = this.feature_infos_;

		if(featureNames.length != featureInfos.length){
			throw new IllegalArgumentException();
		}

		for(int i = 0; i < featureNames.length; i++){
			String featureName = featureNames[i];
			String featureInfo = featureInfos[i];

			if(LightGBMUtil.isNone(featureInfo)){
				features.add(null);

				if(hasPandasCategories){
					pandasCategoryIndex++;
				}

				continue;
			}

			Boolean binary = isBinary(i);
			if(binary == null){
				binary = Boolean.FALSE;
			}

			Boolean categorical = isCategorical(i);
			if(categorical == null){
				categorical = LightGBMUtil.isValues(featureInfo);
			}

			FieldName activeField = FieldName.create(featureNames[i]);

			if(categorical){

				if(binary){
					throw new IllegalArgumentException();
				} else

				{
					Feature feature;

					if(hasPandasCategories){
						List<?> categories = this.pandas_categorical.get(pandasCategoryIndex);

						DataType dataType = TypeUtil.getDataType(categories);
						switch(dataType){
							case INTEGER:
								categories = ((List<String>)categories).stream()
									.map(LightGBMUtil.CATEGORY_PARSER)
									.collect(Collectors.toList());
								break;
							default:
								break;
						}

						DataField dataField = encoder.createDataField(activeField, OpType.CATEGORICAL, dataType, categories);

						feature = new CategoricalFeature(encoder, dataField);

						pandasCategoryIndex++;
					} else

					{
						List<Integer> categories = LightGBMUtil.parseValues(featureInfo).stream()
							.filter(value -> value != GBDT.CATEGORY_MISSING)
							.sorted()
							.collect(Collectors.toList());

						DataField dataField = encoder.createDataField(activeField, OpType.CATEGORICAL, DataType.INTEGER, categories);

						feature = new DirectCategoricalFeature(encoder, dataField);
					}

					features.add(feature);
				}

				encoder.addDecorator(activeField, new InvalidValueDecorator(InvalidValueTreatmentMethod.AS_MISSING, null));
			} else

			{
				if(binary){
					DataField dataField = encoder.createDataField(activeField, OpType.CATEGORICAL, DataType.INTEGER, Arrays.asList(0, 1));

					features.add(new BinaryFeature(encoder, dataField, 1));
				} else

				{
					DataField dataField = encoder.createDataField(activeField, OpType.CONTINUOUS, DataType.DOUBLE);

					Interval interval = LightGBMUtil.parseInterval(featureInfo);

					dataField.addIntervals(interval);

					features.add(new ContinuousFeature(encoder, dataField));
				}

				encoder.addDecorator(activeField, new InvalidValueDecorator(InvalidValueTreatmentMethod.AS_IS, null));
			}

			Double importance = getFeatureImportance(featureName);
			if(importance != null){
				encoder.addDecorator(activeField, new ImportanceDecorator(importance));
			}
		}

		if(hasPandasCategories){

			if(pandasCategoryIndex != this.pandas_categorical.size()){
				throw new IllegalArgumentException();
			}
		}

		return new Schema(label, features);
	}

	public Schema toLightGBMSchema(Schema schema){
		String[] featureNames = this.feature_names_;
		String[] featureInfos = this.feature_infos_;

		Function<Feature, Feature> function = new Function<Feature, Feature>(){

			private List<? extends Feature> features = schema.getFeatures();

			{
				SchemaUtil.checkSize(featureNames.length, this.features);
				SchemaUtil.checkSize(featureInfos.length, this.features);
			}

			@Override
			public Feature apply(Feature feature){
				int index = this.features.indexOf(feature);
				if(index < 0){
					throw new IllegalArgumentException();
				}

				String featureName = featureNames[index];
				String featureInfo = featureInfos[index];

				Double importance = getFeatureImportance(featureName);
				if(importance != null){
					ModelEncoder encoder = (ModelEncoder)feature.getEncoder();

					encoder.addDecorator(feature.getName(), new ImportanceDecorator(importance));
				} // End if

				if(feature instanceof BinaryFeature){
					BinaryFeature binaryFeature = (BinaryFeature)feature;

					Boolean binary = isBinary(index);
					if(binary != null && binary.booleanValue()){
						return binaryFeature;
					}

					Boolean categorical = isCategorical(index);
					if(categorical != null && categorical.booleanValue()){
						CategoricalFeature categoricalFeature = new BinaryCategoricalFeature(binaryFeature.getEncoder(), binaryFeature);

						return categoricalFeature;
					}
				} else

				if(feature instanceof CategoricalFeature){
					CategoricalFeature categoricalFeature = (CategoricalFeature)feature;

					Boolean categorical = isCategorical(index);
					if(categorical != null && categorical.booleanValue()){
						return categoricalFeature;
					}
				} else

				if(feature instanceof WildcardFeature){
					WildcardFeature wildcardFeature = (WildcardFeature)feature;

					Boolean binary = isBinary(index);
					if(binary != null && binary.booleanValue()){
						wildcardFeature.toCategoricalFeature(Arrays.asList(0, 1));

						BinaryFeature binaryFeature = new BinaryFeature(wildcardFeature.getEncoder(), wildcardFeature, 1);

						return binaryFeature;
					}
				}

				return feature.toContinuousFeature();
			}
		};

		return schema.toTransformedSchema(function);
	}

	public PMML encodePMML(FieldName targetField, List<String> targetCategories, Map<String, ?> options){
		LightGBMEncoder encoder = new LightGBMEncoder();

		Schema schema = encodeSchema(targetField, targetCategories, encoder);

		MiningModel miningModel = encodeMiningModel(options, schema);

		PMML pmml = encoder.encodePMML(miningModel);

		return pmml;
	}

	public MiningModel encodeMiningModel(Map<String, ?> options, Schema schema){
		Boolean compact = (Boolean)options.get(HasLightGBMOptions.OPTION_COMPACT);
		Integer numIterations = (Integer)options.get(HasLightGBMOptions.OPTION_NUM_ITERATION);

		MiningModel miningModel = this.object_function_.encodeMiningModel(Arrays.asList(this.models_), numIterations, schema)
			.setAlgorithmName("LightGBM");

		if((Boolean.TRUE).equals(compact)){
			Visitor visitor = new TreeModelCompactor();

			visitor.applyTo(miningModel);
		}

		return miningModel;
	}

	public String[] getFeatureNames(){
		return this.feature_names_;
	}

	public String[] getFeatureInfos(){
		return this.feature_infos_;
	}

	private Boolean isBinary(int feature){
		String featureInfo = this.feature_infos_[feature];

		if(!LightGBMUtil.isBinaryInterval(featureInfo)){
			return Boolean.FALSE;
		}

		Boolean result = null;

		Tree[] trees = this.models_;
		for(Tree tree : trees){
			Boolean binary = tree.isBinary(feature);

			if(binary != null){

				if(!binary.booleanValue()){
					return Boolean.FALSE;
				}

				result = Boolean.TRUE;
			}
		}

		return result;
	}

	private Boolean isCategorical(int feature){
		String featureInfo = this.feature_infos_[feature];

		if(!LightGBMUtil.isValues(featureInfo)){
			return Boolean.FALSE;
		}

		Boolean result = null;

		Tree[] trees = this.models_;
		for(Tree tree: trees){
			Boolean categorical = tree.isCategorical(feature);

			if(categorical != null){

				if(!categorical.booleanValue()){
					return Boolean.FALSE;
				}

				result = Boolean.TRUE;
			}
		}

		return result;
	}

	private Double getFeatureImportance(String featureName){
		String value = this.feature_importances.get(featureName);

		return (value != null ? Double.valueOf(value) : null);
	}

	static
	private ObjectiveFunction loadObjectiveFunction(Section section){
		String[] tokens = section.getStringArray("objective", -1);
		if(tokens.length == 0){
			throw new IllegalArgumentException();
		}

		String objective = tokens[0];

		if(tokens.length > 1){
			section = new Section(section);

			for(int i = 1; i < tokens.length; i++){
				section.put(tokens[i], ':');
			}
		}

		switch(objective){
			// RegressionL2loss
			case "regression":
			case "regression_l2":
			case "mean_squared_error":
			case "mse":
			// RegressionL1loss
			case "regression_l1":
			case "mean_absolute_error":
			case "mae":
			// RegressionHuberLoss
			case "huber":
			// RegressionFairLoss
			case "fair":
				return new Regression();
			// RegressionPoissonLoss
			case "poisson":
			// RegressionGammaLoss
			case "gamma":
			// RegressionTweedieLoss
			case "tweedie":
				return new PoissonRegression();
			case "lambdarank":
				return new Lambdarank();
			case "binary":
				return new BinomialLogisticRegression(section.getDouble("sigmoid"));
			case "multiclass":
				return new MultinomialLogisticRegression(section.getInt("num_class"));
			default:
				throw new IllegalArgumentException(objective);
		}
	}

	private Map<String, String> loadFeatureSection(Section section){
		Map<String, String> result = new LinkedHashMap<>(section);

		(result.keySet()).retainAll(Arrays.asList(this.feature_names_));

		return result;
	}

	private List<List<String>> loadPandasCategorical(Section section){
		List<List<String>> result = new ArrayList<>();

		String id = section.id();

		if(("pandas_categorical:null").equals(id)){
			return result;
		} // End if

		if(!id.startsWith("pandas_categorical:[") || !id.endsWith("]")){
			throw new IllegalArgumentException(id);
		}

		id = id.substring("pandas_categorical:[".length(), id.length() - "]".length());

		while(true){
			int index = id.indexOf(']');

			if(index < 0){
				break;
			}

			String values = id.substring(0, index + 1);

			if(!values.startsWith("[") || !values.endsWith("]")){
				throw new IllegalArgumentException(values);
			}

			values = values.substring("[".length(), values.length() - "]".length());

			result.add(loadPandasCategoryValues(values));

			id = id.substring(index + 1);

			if(id.startsWith(", ")){
				id = id.substring(", ".length());
			}
		}

		if(!("").equals(id)){
			throw new IllegalArgumentException(id);
		}

		return result;
	}

	static
	private List<String> loadPandasCategoryValues(String string){
		String[] values = string.split(",\\s");

		Function<String, String> function = new Function<String, String>(){

			@Override
			public String apply(String string){

				if((string.length() > 1) && (string.startsWith("\"") && string.endsWith("\""))){
					string = string.substring("\"".length(), string.length() - "\"".length());
				}

				return LightGBMUtil.unescape(string);
			}
		};

		return Stream.of(values)
			.map(function)
			.collect(Collectors.toList());
	}

	static
	private int skipEndSection(String id, List<Section> sections, int index){

		if(index < sections.size()){
			Section section = sections.get(index);

			if(section.checkId(id)){
				return (index + 1);
			}
		}

		return index;
	}

	private static final Integer CATEGORY_MISSING = -1;
}