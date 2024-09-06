'use client';
import React, { Component } from "react";

interface WordCloudProps {
  words: WordType[];
  width?: number; // 컴포넌트의 너비
  height?: number; // 컴포넌트의 높이
  opt?: Options;
}

interface WordCloudState {
  loading: boolean;
  width: number;
  height: number;
  resultList: ResultType[];
}

export interface Options {
  maskingImage?: any; // 마스킹 용 이미지
  sorted?: boolean; // 데이터 내림차순 정렬
  debugMode?: boolean; // 디버그모드
  minFontSize?: number; // 폰트의 최소값
  maxFontSize?: number; // 폰트의 최대값
}

export interface WordType {
  word: string;
  value: number;
}

interface WordTypeInner extends WordType {
  width: number;
  height: number;
  fontSize: number;
}

interface ResultType {
  x: number;
  y: number;
  word: string;
  direction?: Direction;
  weight: number;
  fontSize: number;
}

enum Direction {
  portrait = "portrait",
  landscape = "landscape",
}

class WordCloud extends Component<WordCloudProps, WordCloudState> {
  private readonly defaultSize = 250; // 컴포넌트의 기본 크기 가로, 세로 250
  private readonly mainRef = React.createRef<HTMLDivElement>();
  private readonly testRef = React.createRef<HTMLCanvasElement>();
  private offCanvas: HTMLCanvasElement | null = null;
  private textCanvas: HTMLCanvasElement | null = null;

  // measureText 를 위한 버퍼 크기
  private readonly textBufferSize = { w: 1000, h: 200 };
  private maskingResource: any;

  private worker?: Worker;

  constructor(props: WordCloudProps) {
    super(props);
    // 초기 state 설정
    this.state = {
      loading: false,
      width: this.defaultSize,
      height: this.defaultSize,
      resultList: [], // resultList의 초기값
    };
  }

  componentDidMount() {
    this.offCanvas = document.createElement("canvas");
    this.textCanvas = document.createElement("canvas");
    this.init();
  }

  componentDidUpdate(prevProps: Readonly<WordCloudProps>) {
    if (this.props.words !== prevProps.words) {
      this.process();
    }
  }

  // 마킹 컬러 - rgba 스타일
  private get markingColorRGBA() {
    return "rgba(125,255,255,1)";
  }

  // 마킹 컬러 - 합계
  private get markingColorSum() {
    return 125 + 255 * 3;
  }

  private get useShape() {
    return this.maskingResource !== undefined;
  }

  // offcanvas 에서 탐색 시 몇 칸씩 건너띄워서 계산할지 산출, 50x50 기준으로 계산
  private get searchLength() {
    if (window.Worker) {
      // 워커 사용 가능 시 고성능으로 처리
      return 1;
    }
    return Math.max(1, Math.floor(this.state.width / 50));
  }

  // word 가중치 값에 대한 최소, 최대 값 산출 - 자동 폰트 사이즈를 산출하기 위함
  private get minMaxValue() {
    return this.props.words.reduce(
      (rs, w) => {
        rs.min = Math.min(w.value, rs.min);
        rs.max = Math.max(w.value, rs.max);
        return rs;
      },
      { min: 8, max: 0 }
    );
  }

  private init = () => {
    this.updateSize();

    if (this.textCanvas && this.offCanvas) {
      this.textCanvas.width = this.textBufferSize.w;
      this.textCanvas.height = this.textBufferSize.h;

      this.offCanvas.width = this.state.width;
      this.offCanvas.height = this.state.height;
    }

    const { maskingImage } = this.props.opt ?? {};

    if (maskingImage) {
      const i = new Image();
      i.src = maskingImage;
      i.width = this.state.width;
      i.height = this.state.height;
      i.onload = this.process;
      this.maskingResource = i;
    } else {
      this.process();
    }
  };

  private updateSize = () => {
    const { width = this.defaultSize, height = this.defaultSize } =
      this.mainRef.current?.getBoundingClientRect() ?? {};
    this.setState({ width, height });
  };

  private getMaskForegroundColor = (imageData: ImageData) => {
    let max = 0;
    const { data } = imageData;
    for (let i = 0; i < data.length; i += 4) {
      const r = data[i];
      const g = data[i + 1];
      const b = data[i + 2];
      const a = data[i + 3];
      max = Math.max(r + g + b + a, max);
    }

    return max;
  };

  private getTextColor = (weight: number) => {
    const { min, max } = this.minMaxValue;

    const classCount = 7;
    const gap = (max - min) / classCount;

    if (max - gap <= weight && weight <= max) {
      return "#FB3363";
    } else if (max - gap * 2 <= weight && weight < max - gap) {
      return "#06AD85";
    } else if (max - gap * 3 <= weight && weight < max - gap * 2) {
      return "#3353FB";
    } else if (max - gap * 4 <= weight && weight < max - gap * 3) {
      return "#757575";
    } else if (max - gap * 5 <= weight && weight < max - gap * 4) {
      return "#A1A1A1";
    } else if (max - gap * 6 <= weight && weight < max - gap * 5) {
      return "#BABABA";
    }
    return "#000000";
  };

  private buildWordData = (): WordTypeInner[] => {
    const { words, opt = { sorted: true } } = this.props;

    let wordData: WordType[] = [...words];

    if (!opt.sorted) {
      wordData = wordData.sort((a, b) => b.value - a.value);
    }

    return wordData.map((w, i) => {
      const fontSize = this.getFontSize(w.value);
      const { w: width, h: height } = this.measureText(w.word, fontSize);
      return {
        ...w,
        width,
        height,
        fontSize,
      };
    });
  };

  private getFontSize = (weight: number) => {
    const { minFontSize = 14, maxFontSize = 64 } = this.props.opt ?? {};
    const { min, max } = this.minMaxValue;
    const weightPerFontSize = (maxFontSize - minFontSize) / (max - min);
    return Math.floor((weight - min) * weightPerFontSize) + minFontSize;
  };

  private measureText = (text: string, fontSize: number, separator = "▉") => {
    const { textCanvas, textBufferSize } = this;

    const textCtx = textCanvas?.getContext("2d", { willReadFrequently: true });

    if (textCtx) {
      textCtx.font = `${fontSize}px arial`;
      textCtx.textAlign = "left";
      textCtx.textBaseline = "top";
      textCtx.fillStyle = this.markingColorRGBA;

      textCtx.clearRect(0, 0, textBufferSize.w, textBufferSize.h);
      textCtx.fillText(separator, 0, 0);
      const { data: rgbBeforeData = [] } = textCtx.getImageData(
        0,
        0,
        textBufferSize.w,
        textBufferSize.h
      );

      let separatorSize = 0;
      for (let i = 2 * Math.round(fontSize) * 4; i >= 0; i -= 4) {
        const rowIdx = textBufferSize.w * 4 * 2 + i;
        const r = rgbBeforeData[rowIdx];
        const g = rgbBeforeData[rowIdx + 1];
        const b = rgbBeforeData[rowIdx + 2];
        const a = rgbBeforeData[rowIdx + 3];
        if (r + g + b + a === this.markingColorSum) {
          separatorSize = Math.floor(i / 4);
          break;
        }
      }

      textCtx.clearRect(0, 0, textBufferSize.w, textBufferSize.h);
      textCtx.fillText(separator + text + separator, 0, 0);

      const { data: rgbData = [] } = textCtx.getImageData(
        0,
        0,
        textBufferSize.w,
        textBufferSize.h
      );

      let ex = 0;
      for (
        let i = Math.min(
          textBufferSize.w * 4,
          (text.length + 2) * Math.round(fontSize) * 4
        );
        i >= 0;
        i -= 4
      ) {
        const rowIdx = textBufferSize.w * 4 * 2 + i;
        const r = rgbData[rowIdx];
        const g = rgbData[rowIdx + 1];
        const b = rgbData[rowIdx + 2];
        const a = rgbData[rowIdx + 3];
        if (r + g + b + a === this.markingColorSum) {
          ex = Math.floor(i / 4);
          break;
        }
      }

      let ey = 0;
      for (let i = textBufferSize.h - 1; i >= 0; i--) {
        const rowIdx = textBufferSize.w * i * 4;
        const r = rgbData[rowIdx];
        const g = rgbData[rowIdx + 1];
        const b = rgbData[rowIdx + 2];
        const a = rgbData[rowIdx + 3];
        if (r + g + b + a === this.markingColorSum) {
          ey = Math.floor(i);
          break;
        }
      }
      return { w: ex - separatorSize * 2, h: ey };
    }

    return { w: 0, h: 0 };
  };

  private process = () => {
    const ctx = this.offCanvas?.getContext("2d", { willReadFrequently: true });
    if (!ctx) {
      console.error("Canvas 객체를 찾을수 없습니다.");
      return;
    } else if (!window.Worker) {
      console.error("Web Worker 를 지원하지 않습니다.");
      return;
    }

    const { width, height } = this.state;

    ctx.clearRect(0, 0, width, height);
    ctx.textBaseline = "top";
    ctx.textAlign = "left";
    ctx.fillStyle = this.markingColorRGBA;

    if (this.useShape) {
      ctx.drawImage(this.maskingResource, 0, 0, width, height);
    }

    const fgColor = this.getMaskForegroundColor(ctx.getImageData(0, 0, width, height));
    const wordData = this.buildWordData();
    const startX = width / 2;
    const startY = height / 2;

    if (this.worker) {
      this.worker.terminate();
    }

    this.setState({ loading: true, resultList: [] });

    this.worker = new Worker("/worker.js");

    this.worker.postMessage({ type: "start" });

    this.worker.onmessage = (event) => {
      const { type } = event.data;

      if (type === "next" && wordData.length === 0) {
        this.worker?.terminate();
        this.worker = undefined;
        this.setState({ loading: false });

        if (this.props.opt?.debugMode) {
          const realCtx = this.testRef.current?.getContext("2d", {
            willReadFrequently: true,
          });
          if (!realCtx) {
            return;
          }

          realCtx.drawImage(ctx.canvas, 0, 0);
        }
        return;
      }

      if (type === "next") {
        const imageData = ctx.getImageData(0, 0, width, height);
        const direction =
          Math.floor(Math.random() * 2) === 0
            ? Direction.portrait
            : Direction.landscape;
        const word = wordData.shift();

        this.worker?.postMessage({
          type: "process",
          imageData,
          direction,
          word,
          searchLength: this.searchLength,
          fgColor,
          startX,
          startY,
          markingColor: this.markingColorSum,
        });
      } else if (type === "result") {
        const { word, direction, x, y } = event.data ?? {};
        if (!word) {
          console.error("연산 결과가 없습니다.");
          return;
        }

        this.drawMarking(ctx, x, y, word.width, word.height, direction);

        this.setState((prevState) => ({
          resultList: [
            ...prevState.resultList,
            {
              x,
              y,
              word: word.word,
              direction,
              weight: word.value,
              fontSize: word.fontSize,
            },
          ],
        }));
      }
    };
  };

  private drawMarking = (
    ctx: CanvasRenderingContext2D,
    x: number,
    y: number,
    w: number,
    h: number,
    direction: Direction
  ) => {
    if (direction === Direction.portrait) {
      ctx.fillRect(x - h / 2, y - w / 2, h, w);
    } else {
      ctx.fillRect(x - w / 2, y - h / 2, w, h);
    }
  };

  render() {
    const { width = 200, height = 200, opt = { debugMode: false } } = this.props;
    return (
      <div
        className={"word-cloud"}
        ref={this.mainRef}
        style={{ display: "inline-block", width, height }}
      >
        <div
          style={{ position: "relative", width: this.state.width, height: this.state.height }}
        >
          {this.state.resultList.map((w, i) => (
            <span
              key={i}
              style={{
                display: "inline-flex",
                alignItems: "center",
                position: "absolute",
                left: w.x,
                top: w.y,
                fontSize: w.fontSize,
                whiteSpace: "nowrap",
                color: this.getTextColor(w.weight),
                transform: `translate(-50%, -50%) ${
                  w.direction === Direction.portrait ? "rotate(90deg)" : ""
                }`,
              }}
            >
              {w.word}
            </span>
          ))}
          {this.state.loading && (
            <p
              style={{
                position: "absolute",
                left: "50%",
                top: "50%",
                transform: "translate(-50%, -50%)",
              }}
            >
              작업중...
            </p>
          )}
        </div>
        {opt.debugMode && (
          <canvas width={this.state.width} height={this.state.height} ref={this.testRef} />
        )}
      </div>
    );
  }
}

export default WordCloud;
