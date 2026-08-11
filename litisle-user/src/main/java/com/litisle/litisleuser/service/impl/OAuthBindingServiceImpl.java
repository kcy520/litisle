package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleuser.domain.entity.OAuthBinding;
import com.litisle.litisleuser.mapper.OAuthBindingMapper;
import com.litisle.litisleuser.service.OAuthBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthBindingServiceImpl
        extends ServiceImpl<OAuthBindingMapper, OAuthBinding>
        implements OAuthBindingService {

    private final OAuthBindingMapper oAuthBindingMapper;
}