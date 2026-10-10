# 제3자 구성요소 고지

## YOLO11n (센트리 사람·차량 거르기)
- 파일: `app/src/main/assets/models/yolo11n_416.tflite` — Ultralytics YOLO11n(COCO 80종) 가중치를 입력 416으로 TFLite(LiteRT)로 변환한 것.
- 저작권: Ultralytics. 라이선스: **GNU AGPL-3.0** (https://github.com/ultralytics/ultralytics/blob/main/LICENSE).
- 이 모델을 포함해 배포하는 앱의 소스 전체는 AGPL-3.0 조건에 따라 공개한다(운영자 결정 10-07: "소스 공개하면 되지"). 변환 방법: `yolo export model=yolo11n.pt format=tflite imgsz=416`(ultralytics 8.4.x, litert-torch).
- BSD 후측방 인식용 입력 320 모델(운영자 10-10, 속도 목표 0.5초): `app/src/main/assets/models/yolo11n_320_int8.tflite` — 같은 YOLO11n 가중치를 입력 320·int8로 변환(입출력은 float32 NCHW 0~1·[1,84,2100] 그대로, 양자화 보정은 Ultralytics coco8 이미지). 같은 Ultralytics AGPL-3.0. 변환: `yolo export model=yolo11n.pt format=tflite imgsz=320 int8=True data=coco8.yaml`(ultralytics 8.4.175, litert-torch). 입력 320 float32판은 용량(10.7MB)이 커서 앱에 넣지 않고 `tools/models-extra/yolo11n_320.tflite`에 둔다(속도·정확도 비교용).
- 사용 코드: `motiondetect/java/com/cseini/motiondetect/YoloDetector.java` — 모델 출력에서 사람·차량 클래스 점수와 박스(중심·크기 0~1)를 읽고 같은 종류끼리 겹침 제거(NMS)를 한 뒤 점수 상위만 쓴다.

## EfficientDet-Lite0 / MoveNet Lightning (트렁크 킥 감지)
- Apache-2.0 (TensorFlow Hub / Google).
