export interface RocketData {
  memberId: number;
  nickname: string;
  stockPrice: number;
  message: string;
  createdAt: string;
  characterType: number;
}

export interface RocketPriceGroupProps {
  stockPrice: number;
  priceChange: string; // 변동률
  priceChangeSign: string; // 변동률 부호
}

export interface RocketCardProps {
  data: {
    rocketId: number,
    memberId: number;
    characterType: number;
    nickname: string;
    stockPrice: number; // 로켓 작성 당시 주가
    content: string;
    createdAt: string;
  };
  currentPrice: string; // 실시간으로 받아오는 현재 주가
}