#ifndef FASTDVDNET_H
#define FASTDVDNET_H

#include <ncnn/net.h>
#include <ncnn/gpu.h>
#include <vector>
#include <string>

class FastDVDnet {
public:
    FastDVDnet();
    ~FastDVDnet();

    int load(const std::string& param_path, const std::string& bin_path);
    int process(const std::vector<ncnn::Mat>& input_frames, ncnn::Mat& output_frame);

    bool is_loaded() const { return loaded; }

private:
    ncnn::Net net;
    ncnn::VulkanDevice* vkdev;
    bool loaded;
};

#endif // FASTDVDNET_H
