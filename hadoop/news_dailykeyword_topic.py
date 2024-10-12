from pyspark.sql import SparkSession
from pyspark.sql.functions import udf, collect_list, explode, col, current_timestamp, date_format, regexp_replace
from pyspark.sql.types import MapType, StringType, IntegerType
from datetime import datetime, timedelta
import os

# Spark 세션 생성
spark = SparkSession.builder \
    .appName("DailyKeywordAggregation") \
    .getOrCreate()

# HDFS 경로 설정
base_path = "/user/news_preprocessing_testing"
start_year = 2021
end_year = 2021
date_format_str = "%Y.%m.%d"  # 날짜 형식

# 날짜 리스트 생성 함수
def generate_date_list(start_date, end_date):
    date_list = []
    current_date = start_date
    while current_date <= end_date:
        date_list.append(current_date.strftime(date_format_str))
        current_date += timedelta(days=1)
    return date_list

# 시작 및 종료 날짜 설정
start_date = datetime(2021, 1, 10)  # 시작 날짜
end_date = datetime(2021, 1, 11)    # 종료 날짜

# 날짜 리스트 생성
date_list = generate_date_list(start_date, end_date)

# HDFS에서 Parquet 파일 로드
dfs = []  # 모든 DataFrame을 저장할 리스트

for date_str in date_list:
    hdfs_path = os.path.join(base_path, str(start_year), date_str)
    # Parquet 파일 로드
    try:
        df_temp = spark.read.parquet(hdfs_path + "/*.parquet")
        dfs.append(df_temp)
    except Exception as e:
        print(f"파일 로드 실패: {hdfs_path}, 에러: {e}")

# 모든 DataFrame을 하나로 합치기
if dfs:
    df = dfs[0]  # 첫 번째 DataFrame
    for temp_df in dfs[1:]:
        df = df.union(temp_df)

spark_df = df

# 형태소 분석 함수
def analyze_content(content):
    from bareunpy import Tagger
    API_KEY = "koba-TLIK2BA-WPNUG3I-UV4COFQ-I6J62JI"
    tagger = Tagger(API_KEY, 'bareun', 5757)
    try:
        res = list(tagger.tags([content]).pos())
        dict_ = {}
        for keyword in res:
            if keyword[1] in ['NNG', 'NNP', 'NNB']:  # 관심 있는 품사만 선택
                dict_[keyword[0]] = dict_.get(keyword[0], 0) + 1
        return dict_
    except Exception as e:
        return {}

# UDF(User Defined Function)로 변환하여 스파크에서 사용 가능하게 만듦
analyze_content_udf = udf(analyze_content, MapType(StringType(), IntegerType()))

# DataFrame에 형태소 분석 적용
spark_df = spark_df.withColumn("keyword_map", analyze_content_udf(spark_df["content"]))

# 날짜만 추출하여 그룹핑
spark_df = spark_df.withColumn("date_only", col("date").substr(1, 10))  # 날짜만 추출 (YYYY-MM-DD)

# 날짜별로 키워드 집계
aggregated_df = spark_df.groupBy("date_only").agg(
    collect_list("keyword_map").alias("keyword_maps")
)

# 키워드 및 빈도수 계산
final_result = aggregated_df.select(
    "date_only",
    explode(col("keyword_maps")).alias("keyword_map")
).groupBy("date_only") \
    .agg(
        collect_list("keyword_map").alias("keywords")
    )

# 키워드 딕셔너리로 변환
def merge_keywords(keywords):
    merged = {}
    for keyword in keywords:
        for k, v in keyword.items():
            if v > 25:  # 빈도수가 25 이하인 키워드는 제외
                merged[k] = merged.get(k, 0) + v
    return merged

# UDF 등록
merge_keywords_udf = udf(merge_keywords, MapType(StringType(), IntegerType()))

# merged_keywords 생성
final_result = final_result.withColumn("merged_keywords", merge_keywords_udf(col("keywords")))

# 최종 결과를 DataFrame으로 변환
final_df = final_result.select(
    regexp_replace(col("date_only"), r'\.', '-').alias("newsPublishedDate"),  # yyyy-MM-dd 형식으로 변환
    col("merged_keywords").alias("keyword"),
    date_format(current_timestamp(), "yyyy-MM-dd'T'HH:mm:ss").alias("dailyKeywordFrequencyCreatedAt")  # 올바른 형식으로 변환
)

# 결과 Kafka로 발행
final_df.selectExpr("to_json(struct(*)) AS value") \
    .write \
    .format("kafka") \
    .option("kafka.bootstrap.servers", serverport) \
    .option("topic", "DailyStockFrequency-Topic") \
    .save()

print("데이터가 Kafka로 발행되었습니다.")