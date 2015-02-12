// ILayerService.aidl
package jp.takke.datastats;

oneway interface ILayerService {

    void restart();

    void stop();

    void startSnapshot(long previewBytes);
}
