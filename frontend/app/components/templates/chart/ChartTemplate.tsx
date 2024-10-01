"use client";

import styled from "@emotion/styled";
import { useEffect, useState } from "react";
import { init } from "klinecharts";

import useKRChartWebSocket from "@/app/hooks/useKRChartWebSocket";

const ChartContainer = styled.div`
  width: auto;
  height: 500px;
  overflow: hidden;
  background-color: #111;
`;

const OptionContainer = styled.div`
  display: flex;
  flex-direction: row;
  gap: 10px;
`;

const Option = styled.div`
  background-color: white;
  padding: 10px;
  border-radius: 5px;
  cursor: pointer;
`;

const ChartTemplate = () => {
  const [chartContainerRef, setChartContainerRef] = useState(null);
  const [chart, setChart] = useState<any>(null);

  useEffect(() => {
    if (chartContainerRef) {
      const newChart = init(chartContainerRef);

      newChart?.createIndicator("MA", false, { id: "candle_pane" });
      newChart?.createIndicator("VOL");
      // newChart?.applyNewData(genData("minuate"));

      newChart?.setStyles({
        grid: {
          horizontal: {
            color: "rgba(255, 255, 255, 0.1)",
            size: 1,
          },
          vertical: {
            color: "rgba(255, 255, 255, 0.1)",
            size: 1,
          },
        },
        // candle: {
        //   tooltip: {
        //     custom: [
        //       { title: "time", value: "{time}" },
        //       { title: "open", value: "{open}" },
        //       { title: "high", value: "{high}" },
        //       { title: "low", value: "{low}" },
        //       { title: "close", value: "{close}" },
        //       { title: "volume", value: "{volume}" },
        //     ],
        //   },
        // },
      });

      setChart(newChart);
    }
  }, [chartContainerRef]);

  interface CoinData {
    market: string;
    korean_name: string;
    english_name: string;
  }

  interface CoinState {
    currentPrice: number | null;
    changePrice: number | null;
    changeRate: number | null;
  }

  useKRChartWebSocket("005930", chart);

  const genData = (temp: string) => {
    console.log(temp);
  };

  return (
    <div>
      <OptionContainer>
        <Option onClick={() => chart?.applyNewData(genData("minute"))}>
          1분
        </Option>
        <Option onClick={() => chart?.applyNewData(genData("day"))}>일</Option>
        <Option onClick={() => chart?.applyNewData(genData("week"))}>주</Option>
        <Option onClick={() => chart?.applyNewData(genData("month"))}>
          월
        </Option>
        <Option onClick={() => chart?.applyNewData(genData("year"))}>년</Option>
      </OptionContainer>
      <ChartContainer
        ref={(el: any) => setChartContainerRef(el)} // ref 콜백을 사용하여 상태 업데이트
      ></ChartContainer>
    </div>
  );
};

export default ChartTemplate;