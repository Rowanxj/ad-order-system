package com.course.adorder.service;

import com.course.adorder.common.BusinessException;
import com.course.adorder.dto.RequestDtos;
import com.course.adorder.entity.Customer;
import com.course.adorder.mapper.CustomerMapper;
import com.course.adorder.security.LoginUser;
import org.springframework.stereotype.Service;

import java.util.List;

/** 客户管理 */
@Service
public class CustomerService {

    private final CustomerMapper customerMapper;

    public CustomerService(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    public List<Customer> list(String keyword) {
        return customerMapper.selectList(keyword);
    }

    public Customer create(LoginUser user, RequestDtos.CustomerCreateRequest req) {
        Customer c = new Customer();
        c.setName(req.name());
        c.setContactPerson(req.contactPerson());
        c.setContactPhone(req.contactPhone());
        c.setIndustry(req.industry());
        c.setRemark(req.remark());
        c.setCreatedBy(user.getId());
        customerMapper.insert(c);
        return c;
    }

    public Customer requireExists(Long id) {
        Customer c = customerMapper.selectById(id);
        if (c == null) {
            throw new BusinessException(BusinessException.NOT_FOUND, "客户不存在");
        }
        return c;
    }
}
