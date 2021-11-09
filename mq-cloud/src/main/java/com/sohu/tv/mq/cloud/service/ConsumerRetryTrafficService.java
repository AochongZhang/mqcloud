package com.sohu.tv.mq.cloud.service;

import com.sohu.tv.mq.cloud.util.MarkdownBuilder;
import org.apache.rocketmq.store.stats.BrokerStatsManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sohu.tv.mq.cloud.bo.TopicConsumer;
import com.sohu.tv.mq.cloud.bo.TopicHourTraffic;
import com.sohu.tv.mq.cloud.util.MQCloudConfigHelper;

/**
 * 消费者重试消息监控
 * 
 * @Description:
 * @author yongfeigao
 * @date 2018年6月26日
 */
@Service
public class ConsumerRetryTrafficService extends HourTrafficService{

    @Autowired
    private AlertService alertService;

    @Autowired
    private MQCloudConfigHelper mqCloudConfigHelper;

    @Autowired
    private AlarmConfigBridingService alarmConfigBridingService;
    
    protected void alert(TopicHourTraffic topicTraffic, TopicConsumer topicConsumer, String email) {
        long consumerFailCount = alarmConfigBridingService.getConsumerFailCount(topicConsumer.getConsumer());
        if (consumerFailCount >= 0 && consumerFailCount < topicTraffic.getCount()) {
            // 验证报警频率
            if (alarmConfigBridingService.needWarn("consumerFail", topicConsumer.getTopic(), topicConsumer.getConsumer())) {
                alertService.sendWarnMail(email, "消费失败", "topic:<b>" + topicConsumer.getTopic() + "</b> 消费者:<b>"
                        + mqCloudConfigHelper.getTopicConsumeLink(topicConsumer.getTid(), topicConsumer.getConsumer(),
                                System.currentTimeMillis())
                        + "</b> 消费失败量:" + topicTraffic.getCount() 
                        + ", <a href='" + mqCloudConfigHelper.getTopicConsumeHref(topicConsumer.getTid(),
                                topicConsumer.getConsumer(), topicConsumer.getCid(), 0)
                        + "'>跳过重试消息</a>?");
                MarkdownBuilder markdownBuilder = new MarkdownBuilder();
                markdownBuilder.title2("MQCloud 消费失败预警").line();
                markdownBuilder.title3("Topic").text(topicConsumer.getTopic()).doReturn();
                markdownBuilder.title3("消费者").link(topicConsumer.getConsumer(), mqCloudConfigHelper
                        .getTopicConsumeHref(topicConsumer.getTid(), topicConsumer.getConsumer(), -1, System.currentTimeMillis()));
                markdownBuilder.title3("消费失败量").text(String.valueOf(topicTraffic.getCount())).doReturn();
                markdownBuilder.link("跳过重试消息", mqCloudConfigHelper.getTopicConsumeHref(topicConsumer.getTid(),
                        topicConsumer.getConsumer(), topicConsumer.getCid(), 0));
                alertService.sendWarnDingTalk("消费失败", markdownBuilder.build());
            }
        }
    }

    protected String getCountKey() {
        return BrokerStatsManager.SNDBCK_PUT_NUMS;
    }
}
