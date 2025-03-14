package cn.zzq0324.alarm.bot.core.extension.platform.impl.lark;

import cn.zzq0324.alarm.bot.core.constant.LarkConstants;
import cn.zzq0324.alarm.bot.core.extension.platform.impl.lark.parser.LarkMessageParserExt;
import cn.zzq0324.alarm.bot.core.spi.ExtensionLoader;
import cn.zzq0324.alarm.bot.core.vo.IMMessage;
import cn.zzq0324.alarm.bot.core.vo.LarkGetUserIdRequest;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.larksuite.oapi.core.Config;
import com.larksuite.oapi.core.api.AccessTokenType;
import com.larksuite.oapi.core.api.Api;
import com.larksuite.oapi.core.api.ReqCaller;
import com.larksuite.oapi.core.api.request.Request;
import com.larksuite.oapi.core.api.request.RequestOptFn;
import com.larksuite.oapi.core.api.response.Response;
import com.larksuite.oapi.service.bot.v3.BotService;
import com.larksuite.oapi.service.bot.v3.model.BotInfo;
import com.larksuite.oapi.service.contact.v3.ContactService;
import com.larksuite.oapi.service.contact.v3.model.User;
import com.larksuite.oapi.service.im.v1.ImService;
import com.larksuite.oapi.service.im.v1.model.ChatCreateReqBody;
import com.larksuite.oapi.service.im.v1.model.ChatCreateResult;
import com.larksuite.oapi.service.im.v1.model.ChatListResult;
import com.larksuite.oapi.service.im.v1.model.ChatMembersCreateReqBody;
import com.larksuite.oapi.service.im.v1.model.EventMessage;
import com.larksuite.oapi.service.im.v1.model.ListChat;
import com.larksuite.oapi.service.im.v1.model.Message;
import com.larksuite.oapi.service.im.v1.model.MessageCreateReqBody;
import com.larksuite.oapi.service.im.v1.model.MessageListResult;
import com.larksuite.oapi.service.im.v1.model.MessageReceiveEventData;
import com.larksuite.oapi.service.im.v1.model.MessageReplyReqBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * description: LarkHelper <br>
 * date: 2022/2/21 1:31 下午 <br>
 * author: zzq0324 <br>
 * version: 1.0 <br>
 */
@Component
@Slf4j
public class LarkHelper {

    private static final RequestOptFn TIMEOUT_OPT = Request.setTimeout(2, TimeUnit.MINUTES);

    private static final int PAGE_SIZE = 50;

    private static BotInfo BOT_INFO = null;

    @Autowired
    private Config config;

    /**
     * 获取飞书用户信息
     */
    public User getLarkUser(String userId, String userIdType) {
        ContactService contactService = new ContactService(config);
        ContactService.UserGetReqCall caller =
            contactService.getUsers().get(TIMEOUT_OPT).setUserId(userId).setUserIdType(userIdType);

        return executeCaller(caller).getUser();
    }

    /**
     * 添加成员到群组
     */
    public void addMemberToChatGroup(String chatGroupId, List<String> memberIdList) {
        ImService imService = new ImService(config);
        ChatMembersCreateReqBody reqBody = new ChatMembersCreateReqBody();
        reqBody.setIdList(memberIdList.toArray(new String[memberIdList.size()]));

        executeCaller(imService.getChatMemberss().create(reqBody, TIMEOUT_OPT).setChatId(chatGroupId));
    }

    /**
     * 创建群聊
     */
    public String createChatGroup(String name, String description) {
        ImService imService = new ImService(config);
        ChatCreateReqBody reqBody = new ChatCreateReqBody();
        reqBody.setName(name);
        reqBody.setDescription(description);

        ImService.ChatCreateReqCall caller = imService.getChats().create(reqBody, TIMEOUT_OPT);
        // 反射，设置机器人为群主
        Field field = ReflectionUtils.findField(ImService.ChatCreateReqCall.class, "queryParams");
        ReflectionUtils.makeAccessible(field);
        Map<String, Object> queryParma = (Map<String, Object>)ReflectionUtils.getField(field, caller);
        queryParma.put("set_bot_manager", true);

        ChatCreateResult result = executeCaller(caller);

        log.info("create lark chat group, name: {}, chatGroupId: {}", name, result.getChatId());

        return result.getChatId();
    }

    /**
     * 解散群聊
     */
    public void destroyChatGroup(String chatGroupId) {
        ImService imService = new ImService(config);

        executeCaller(imService.getChats().delete(TIMEOUT_OPT).setChatId(chatGroupId));
    }

    public IMMessage parse(MessageReceiveEventData eventData) {
        EventMessage eventMessage = eventData.getMessage();

        LarkMessageParserExt messageParser =
            ExtensionLoader.getExtension(LarkMessageParserExt.class, eventMessage.getMessageType());

        // 不支持的解析，不做处理
        if (messageParser == null) {
            return null;
        }

        IMMessage imMessage = new IMMessage();
        messageParser.parse(imMessage, eventData);

        return imMessage;
    }

    /**
     * 下载聊天信息
     *
     * @param chatId    群组ID
     * @param startTime 开始时间，秒为单位
     * @param endTime   结束时间，秒为单位
     */
    public List<Message> downloadChatMessage(String chatId, long startTime, long endTime) {
        List<Message> messageList = new ArrayList<>();

        ImService imService = new ImService(config);
        ImService.MessageListReqCall caller =
            imService.getMessages().list(TIMEOUT_OPT).setContainerIdType("chat").setContainerId(chatId)
                .setPageSize(PAGE_SIZE);

        if (startTime > 0) {
            caller.setStartTime(String.valueOf(startTime));
        }

        if (endTime > 0) {
            caller.setEndTime(String.valueOf(endTime));
        }

        boolean hasMore = true;
        while (hasMore) {
            MessageListResult result = executeCaller(caller);
            messageList.addAll(Arrays.asList(result.getItems()));

            if (result.getHasMore()) {
                caller.setPageToken(result.getPageToken());
            } else {
                hasMore = false;
            }
        }

        return messageList;
    }

    public BotInfo getBotInfo() {
        // 暂时不考虑并发问题
        if (BOT_INFO != null) {
            return BOT_INFO;
        }

        BotService botService = new BotService(config);

        BotService.BotGetReqCall caller = botService.getBots().get(TIMEOUT_OPT);

        BOT_INFO = executeCaller(caller).getBot();

        return BOT_INFO;
    }

    public void sendWebHookMsg(String webHookUrl, String title, String message) {
        Map<String, String> params = new HashMap<>();
        params.put("msg_type", "text");
        params.put("content", buildTextContent(title, message));

        Request request = Request.newRequest(webHookUrl, "POST", AccessTokenType.Tenant, params, new JSONObject());
        invoke(request, false);
    }

    public List<ListChat> getRobotChatGroups() {
        List<ListChat> chatList = new ArrayList<>();
        ImService imService = new ImService(config);
        ImService.ChatListReqCall caller = imService.getChats().list(TIMEOUT_OPT).setPageSize(PAGE_SIZE);
        boolean hasMore = true;

        while (hasMore) {
            ChatListResult result = executeCaller(caller);
            chatList.addAll(Arrays.asList(result.getItems()));
            if (result.getHasMore()) {
                caller.setPageToken(result.getPageToken());
            } else {
                hasMore = false;
            }
        }

        return chatList;
    }

    /**
     * 发送消息
     */
    public void send(String receiveId, String messageType, String content) {
        ImService imService = new ImService(config);
        MessageCreateReqBody reqBody = new MessageCreateReqBody();
        reqBody.setMsgType(messageType);
        reqBody.setContent(content);
        reqBody.setReceiveId(receiveId);

        // receiveIdType为chat_id代表群组id
        executeCaller(imService.getMessages().create(reqBody, TIMEOUT_OPT).setReceiveIdType("chat_id"));
    }

    /**
     * 回复消息
     */
    public void reply(String messageId, String messageType, String content) {
        ImService imService = new ImService(config);
        MessageReplyReqBody reqBody = new MessageReplyReqBody();
        reqBody.setMsgType(messageType);
        reqBody.setContent(content);

        executeCaller(imService.getMessages().reply(reqBody, TIMEOUT_OPT).setMessageId(messageId));
    }

    /**
     * 发送文本
     */
    public void sendText(String receiveId, String title, String text) {
        send(receiveId, LarkConstants.MESSAGE_TYPE_TEXT, buildTextContent(title, text));
    }

    /**
     * 回复文本
     */
    public void replyText(String messageId, String title, String text) {
        reply(messageId, LarkConstants.MESSAGE_TYPE_TEXT, buildTextContent(title, text));
    }

    /**
     * 下载资源文件
     */
    public byte[] downloadResource(String messageId, String fileKey, String type) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImService imService = new ImService(config);

            ImService.MessageResourceGetReqCall caller =
                imService.getMessageResources().get(TIMEOUT_OPT).setMessageId(messageId).setFileKey(fileKey)
                    .setResponseStream(outputStream).setType(type);

            executeCaller(caller);

            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 构建文本内容
     */
    private String buildTextContent(String title, String text) {
        JSONObject content = new JSONObject();
        content.put("text", text);
        if (StringUtils.hasLength(title)) {
            content.put("title", title);
        }

        return content.toJSONString();
    }

    public String getOpenIdByIdentify(String identify) {
        LarkGetUserIdRequest params = new LarkGetUserIdRequest();
        if (identify.indexOf("@") > 0) {
            params.setEmails(new String[] {identify});
        } else {
            params.setMobiles(new String[] {identify});
        }

        Request<LarkGetUserIdRequest, JSONObject> request =
            Request.newRequest("contact/v3/users/batch_get_id", "POST", AccessTokenType.Tenant, params,
                new JSONObject());

        JSONObject response = invoke(request, false);
        JSONArray userList = response.getJSONArray("user_list");
        if (userList.size() > 0) {
            JSONObject userInfo = userList.getJSONObject(0);
            return userInfo.getString("user_id");
        }

        return null;
    }

    private <T> T executeCaller(ReqCaller<?, T> caller) {
        try {
            Response<T> response = caller.execute();
            checkHttpStatus(response);

            return response.getData();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <I, O> O invoke(Request<I, O> request, boolean isCheckHttpStatus) {
        try {
            request.getRequestOptFns().add(TIMEOUT_OPT);
            Response<O> response = Api.send(config, request);

            if (isCheckHttpStatus) {
                checkHttpStatus(response);
            }

            return response.getData();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private <I, O> O invoke(Request<I, O> request) {
        return invoke(request, true);
    }

    private void checkHttpStatus(Response response) {
        if (response.getHTTPStatusCode() != HttpStatus.OK.value()) {
            log.error("invoke feishu error, response: {}", JSONObject.toJSONString(response));

            throw new RuntimeException("invoke feishu error");
        }
    }
}
