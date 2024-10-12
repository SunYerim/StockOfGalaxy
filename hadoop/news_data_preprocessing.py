from pyspark.sql import functions as F
from datetime import datetime, timedelta
from pyspark.ml.feature import Tokenizer, HashingTF, IDF
from sklearn.metrics.pairwise import cosine_similarity
from pyspark.sql import DataFrame
import numpy as np
import time

# "본문 없음" 또는 "Error" 데이터를 필터링하는 함수
def filter_invalid_content(df):    
    # "content" 컬럼에서 '본문 없음' 또는 'Error'가 포함된 행을 제외
    filtered_df = df.filter(~(F.col("content").contains("본문 없음") | F.col("content").contains("Error")))
    filtered_count = filtered_df.count()  # 필터링 후 데이터 개수
    
    return filtered_df

# 날짜별로 데이터를 필터링하는 함수
def filter_by_date(df, date):
    result_df = df.filter(F.col('date').startswith(date))
    return result_df

# 코사인 유사도를 계산하고, 유사도가 0.7 이상인 기사를 제거하는 함수
def remove_similar_articles(df: DataFrame) -> (DataFrame, int):
    # Tokenizer를 이용해 content 컬럼을 토큰화
    tokenizer = Tokenizer(inputCol="content", outputCol="words")
    wordsData = tokenizer.transform(df)
    
    # HashingTF로 각 단어를 TF(Term Frequency)로 변환
    hashingTF = HashingTF(inputCol="words", outputCol="rawFeatures", numFeatures=10000)
    featurizedData = hashingTF.transform(wordsData)
    
    # IDF(Inverse Document Frequency)를 이용해 TF-IDF로 변환
    idf = IDF(inputCol="rawFeatures", outputCol="features")
    idfModel = idf.fit(featurizedData)
    rescaledData = idfModel.transform(featurizedData)
    
    # 벡터를 RDD로 변환
    feature_vectors = rescaledData.select('features').rdd.map(lambda row: row.features.toArray()).collect()
    
    # 전체 유사도 매트릭스 계산
    similarity_matrix = cosine_similarity(feature_vectors)
    
    # 유사한 기사 필터링
    remove_indices = set()
    for i in range(len(similarity_matrix)):
        if i in remove_indices:
            continue
        for j in range(i + 1, len(similarity_matrix)):
            if similarity_matrix[i][j] >= 0.7:
                remove_indices.add(j)
                break
            
    # 중복되지 않은 데이터만 남기기
    filtered_rows = [row for idx, row in enumerate(df.collect()) if idx not in remove_indices]
    filtered_df = spark.createDataFrame(filtered_rows, df.schema)
    
    # 중복 처리된 데이터 개수
    removed_count = len(remove_indices)
    return filtered_df, removed_count

# HDFS에 날짜별로 파티셔닝하여 개별 파일로 저장하는 함수
def save_partitioned_by_date(df, output_path):
    df_with_date_only = df.withColumn('date_only', F.col('date'))
    df_with_date_only.write \
        .partitionBy("date_only") \
        .mode("overwrite") \
        .parquet(output_path)

# HDFS에 날짜별로 파티셔닝하여 단일 파일로 저장하는 함수
def save_to_single_file(df, output_path):
    df.coalesce(1).write \
        .mode("overwrite") \
        .parquet(output_path)

# 전체 로직 - 실행 시간과 데이터 개수를 출력
def process_and_save(df, date_list, output_path):
    for date in date_list:
        print(f"Processing data for date: {date}")
        # 날짜별로 필터링
        df_filtered = filter_by_date(df, date)
        original_count = df_filtered.count()  # 원본 데이터 개수
        
        # 코사인 유사도를 기반으로 중복 기사 제거
        df_unique, removed_count = remove_similar_articles(df_filtered)
        unique_count = df_unique.count()  # 중복 제거된 후 데이터 개수
        
        # 날짜별로 파티셔닝하여 HDFS에 저장
        save_to_single_file(df_unique, f"{output_path}/{date}")

# 날짜 리스트 생성 함수
def generate_date_range(start_date, end_date):
    date_list = []
    current_date = start_date
    while current_date <= end_date:
        date_list.append(current_date.strftime('%Y.%m.%d'))
        current_date += timedelta(days=1)
    return date_list

# 시작 날짜 및 종료 날짜 설정
start_date = datetime(2022, 1, 11)
end_date = datetime(2022, 1, 11)

# 날짜 리스트 생성
date_list = generate_date_range(start_date, end_date)

# MongoDB로부터 데이터를 로드
df = spark.read \
    .format("mongodb") \
    .option("uri", "mongodb://localhost:27018/news_db.news_collection1") \
    .load()

# "본문 없음"과 "Error" Content가 필터링 된 데이터
df_filtered = filter_invalid_content(df)

# 날짜별로 데이터를 처리한 후 HDFS에 저장
output_path = "/user/baejun/news_data_partitioned_by_date"
process_and_save(df_filtered, date_list, output_path)
