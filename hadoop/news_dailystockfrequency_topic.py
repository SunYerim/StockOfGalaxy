from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import StructType, StructField, StringType, IntegerType
import os
from datetime import datetime, timedelta
from functools import reduce
from pyspark.sql import functions as F
from pyspark.sql.functions import col, current_timestamp, date_format, regexp_replace

# Spark 세션 생성
spark = SparkSession.builder \
    .appName("StockMentionCount") \
    .getOrCreate()

# HDFS 경로 설정
base_path = "/user/news_preprocessing_result"
start_year = 2024
end_year = 2024
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
start_date = datetime(2024, 10, 6)  # 시작 날짜
end_date = datetime(2024, 10, 6)    # 종료 날짜

# 날짜 리스트 생성
date_list = generate_date_list(start_date, end_date)

# 키워드 그룹 정의
keyword_groups = {
    "삼성전자": ["삼전", "삼성", "갤럭시", "비스포크"],
    "SK하이닉스": ["SK하이닉스", "하이닉스", "반도체", "메모리"],
    "현대차": ["현대차", "아이오닉", "아반떼"],
    "셀트리온": ["셀트리온", "바이오" ],
    "KB금융": ["KB금융", "국민은행"],
    "POSCO홀딩스": ["POSCO", "포스코", "철강", "제철"],
    "기아": ["기아", "기아차", "카니발", "k3","k7"],
    "신한지주": ["신한지주", "신한", "SOL"],
    "NAVER": ["NAVER", "네이버", "CLOVA"],
    "삼성SDI": ["삼성SDI", "SDI", "배터리"],
    "삼성바이오로직스": ["삼성바이오로직스", "바이오"],
    "LG화학": ["LG화학","석유"],
    "하나금융지주": ["하나금융", "하나은행"],
    "LG에너지솔루션": ["LG에너지솔루션", "LG에너지"],
    "현대모비스": ["현대모비스", "모비스"],
    "삼성물산": ["삼성물산", "물산"],
    "LG전자": ["LG전자", "LG", "가전"],
    "카카오": ["카카오", "Kakao"],
    "삼성화재": ["삼성화재"],
    "우리금융지주": ["우리금융", "우리은행"],
    "크래프톤": ["크래프톤", "배틀그라운드"],
    "KT": ["KT"],
    "SK텔레콤": ["SK텔레콤", "SKT"],
    "삼성생명": ["삼성생명"],
    "LG": ["LG", "LG그룹"],
    "삼성전기": ["삼성전기"],
    "카카오뱅크": ["카카오뱅크", "카뱅"],
    "삼성중공업": ["삼성중공업"],
    "SK이노베이션": ["SK이노베이션", "SK이노", "화학"],
    "삼성에스디에스": ["삼성에스디에스", "SDS"],
    "대한항공": ["대한항공"],
    "SK": ["SK", "SK그룹"],
    "DB손해보험": ["DB손해보험", "손해보험"],
    "HD현대중공업": ["HD현대중공업", "현대중공업"],
    "기업은행": ["기업은행"],
    "한국항공우주": ["한국항공우주"],
    "엔씨소프트": ["엔씨소프트", "NC", "리니지"],
    "하이브": ["하이브", "BTS", "방탄소년단","르세라핌","뉴진스"],
    "LG디스플레이": ["LG디스플레이", "디스플레이", "패널"],
    "SK바이오팜": ["SK바이오팜", "바이오", "제약"]
}

# 키워드 빈도수 계산 함수
def calculate_keyword_frequencies(df, keyword_groups, date):
    frequencies = []
    
    for stock, keywords in keyword_groups.items():
        # 각 키워드에 대해 contains 조건을 생성
        conditions = [F.col("title").contains(keyword) for keyword in keywords]
        # 모든 키워드 조건을 OR로 결합
        filter_condition = reduce(lambda a, b: a | b, conditions)
        
        count = df.filter(filter_condition).count()
        
        if count > 0:
            frequencies.append((stock, count, date))
    
    return frequencies

# 전체 로직 - 날짜별로 파티셔닝된 데이터를 읽고 키워드 빈도수를 계산
all_keyword_frequencies = []

for date_str in date_list:
    # 날짜별로 각 Parquet 파일 경로 설정
    hdfs_path = os.path.join(base_path, str(start_year), date_str)
    try:
        df_temp = spark.read.parquet(hdfs_path + "/*.parquet")
        # 날짜별 키워드 빈도수 계산
        keyword_frequencies = calculate_keyword_frequencies(df_temp, keyword_groups, date_str)
        all_keyword_frequencies.extend(keyword_frequencies)
    except Exception as e:
        print(f"파일 로드 실패: {hdfs_path}, 에러: {e}")

# 결과를 DataFrame으로 변환
schema = StructType([
    StructField("stockName", StringType(), True),
    StructField("count", IntegerType(), True),
    StructField("newsPublishedDate", StringType(), True)
])

# DataFrame 생성
keyword_freq_df = spark.createDataFrame(all_keyword_frequencies, schema)

# newsPublishedDate 형식 변경 및 dailyStockFrequencyCreatedAt 필드 추가
final_df = keyword_freq_df.select(
    regexp_replace(col("newsPublishedDate"), r'\.', '-').alias("newsPublishedDate"),  # yyyy-MM-dd 형식으로 변환
    col("stockName"),
    col("count"),
    date_format(current_timestamp(), "yyyy-MM-dd'T'HH:mm:ss").alias("dailyStockFrequencyCreatedAt")  # 새로운 필드 추가
)

# 결과 Kafka로 발행
final_df.selectExpr("to_json(struct(*)) AS value") \
    .write \
    .format("kafka") \
    .option("kafka.bootstrap.servers", serverport) \
    .option("topic", "DailyStockFrequency-Topic") \
    .save()
