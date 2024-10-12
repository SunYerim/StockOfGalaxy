from pyspark.sql import SparkSession
from pyspark.sql.functions import to_json, struct, lit, col, to_timestamp, current_timestamp, regexp_replace, date_format, when, udf
from pyspark.sql.types import ArrayType, StringType
import os
from datetime import datetime, timedelta

# Spark 세션 생성
spark = SparkSession.builder \
    .appName("KafkaProducer") \
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
start_date = datetime(2024, 1, 1)  # 시작 날짜
end_date = datetime(2024, 10, 12)    # 종료 날짜

# 날짜 리스트 생성
date_list = generate_date_list(start_date, end_date)

# HDFS에서 Parquet 파일 로드
dfs = []  # 모든 DataFrame을 저장할 리스트

for year in range(start_year, end_year + 1):
    for date_str in date_list:
        hdfs_path = os.path.join(base_path, str(year), date_str)
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

# 필드명 변환 및 추가 필드 적용
transformed_df = df.withColumnRenamed("_id", "newsId") \
    .withColumnRenamed("title", "title") \
    .withColumnRenamed("content", "content") \
    .withColumnRenamed("img_src", "thumbnailImg") \
    .withColumnRenamed("link", "newsLink") \
    .withColumnRenamed("date", "publishedDate") \
    .withColumnRenamed("category1", "category") \
    .withColumn("sentimentIndex", lit(0.0)) \
    .withColumn("newsCreatedAt", current_timestamp())

# 카테고리를 ENUM으로 변환
transformed_df = transformed_df.withColumn(
    "category",
    when(col("category") == "정치", "정치")
    .when(col("category") == "경제", "경제")
    .when(col("category") == "사회", "사회")
    .when(col("category") == "기술", "기술")
    .when(col("category") == "스포츠", "스포츠")
    .when(col("category") == "연예", "연예")
    .when(col("category") == "세계", "세계")
    .when(col("category") == "날씨", "날씨")
    .when(col("category") == "건강", "건강")
    .when(col("category") == "생활", "생활")
    .otherwise(None)
)

# Spark 세션에서 시간 파싱 정책을 LEGACY로 설정
spark.conf.set("spark.sql.legacy.timeParserPolicy", "LEGACY")

# publishedDate를 "yyyy-MM-dd'T'HH:mm:ss" 형식으로 변환
transformed_df = transformed_df.withColumn(
    "publishedDate",
    to_timestamp(
        regexp_replace(
            regexp_replace(col("publishedDate"), "오후", "PM"), "오전", "AM"
        ),
        "yyyy.MM.dd. a hh:mm"
    )
)

# publishedDate를 "yyyy-MM-dd'T'HH:mm:ss" 형식으로 변환
transformed_df = transformed_df.withColumn(
    "publishedDate",
    date_format(col("publishedDate"), "yyyy-MM-dd'T'HH:mm:ss")
)

# newsCreatedAt 컬럼 추가 (현재 시간, 형식: yyyy-MM-dd'T'HH:mm:ss)
transformed_df = transformed_df.withColumn(
    "newsCreatedAt", 
    date_format(current_timestamp(), "yyyy-MM-dd'T'HH:mm:ss")
)

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
def extract_keywords(title):
    keywords = []
    for stock, keywords_group in keyword_groups.items():
        if any(keyword in title for keyword in keywords_group):
            keywords.append(stock)
    return keywords

# UDF 등록
extract_keywords_udf = udf(extract_keywords, ArrayType(StringType()))

# 뉴스 데이터에 키워드 열 추가
transformed_df = transformed_df.withColumn("keywords", extract_keywords_udf(col("title")))

# newsId를 제외하고 Kafka로 전송하기 위해 JSON 형식으로 변환
transformed_df.drop("newsId").selectExpr("to_json(struct(*)) AS value") \
    .write \
    .format("kafka") \
    .option("kafka.bootstrap.servers", serverport) \
    .option("topic", "NEWS-Topic") \
    .save()
