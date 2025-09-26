package com.zzw.chatserver.handler;

import com.corundumstudio.socketio.SocketIOServer;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.SystemUser;
import com.zzw.chatserver.service.SuperUserService;
import com.zzw.chatserver.service.SysService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
public class SocketServerHandler implements ApplicationRunner {

    @Resource
    private SocketIOServer socketIOServer;

    @Resource
    private SysService sysService;

    @Resource
    private SuperUserService superUserService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        //初始化一个系统用户
        log.info("-----------initSystemUser-------------");
        initSystemUser();
        //初始化一个管理员账号（默认注册）
        log.info("-----------initSuperUser-------------");
        initSuperUser();
        log.info("-----------socket server start-----------");
        socketIOServer.start();
    }

    private void initSystemUser() {
        SystemUser systemUser = new SystemUser();
        systemUser.setCode("111111");
        systemUser.setNickname("验证消息");
        systemUser.setStatus(1);
        sysService.notExistThenAddSystemUser(systemUser);
    }


    private void initSuperUser() {
        SuperUser superUser = new SuperUser();
        superUser.setAccount("admin");
        superUser.setPassword("admin");
        superUser.setRole(0);
        superUserService.notExistThenAddSuperUser(superUser);
    }
}
