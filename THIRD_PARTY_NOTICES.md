# 제3자 구성요소 고지

## YOLO11n (센트리 사람·차량 거르기)
- 파일: `app/src/main/assets/models/yolo11n_416.tflite` — Ultralytics YOLO11n(COCO 80종) 가중치를 입력 416으로 TFLite(LiteRT)로 변환한 것.
- 저작권: Ultralytics. 라이선스: **GNU AGPL-3.0** (https://github.com/ultralytics/ultralytics/blob/main/LICENSE).
- 이 모델을 포함해 배포하는 앱의 소스 전체는 AGPL-3.0 조건에 따라 공개한다(운영자 결정 10-07: "소스 공개하면 되지"). 변환 방법: `yolo export model=yolo11n.pt format=tflite imgsz=416`(ultralytics 8.4.x, litert-torch).
- 사용 코드: `motiondetect/java/com/cseini/motiondetect/YoloDetector.java` — 모델 출력에서 사람·차량 클래스 점수와 박스(중심·크기 0~1)를 읽고 같은 종류끼리 겹침 제거(NMS)를 한 뒤 점수 상위만 쓴다.

## EfficientDet-Lite0 / MoveNet Lightning (트렁크 킥 감지)
- Apache-2.0 (TensorFlow Hub / Google).
