package com.course.adorder.mapper;

import com.course.adorder.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CustomerMapper {

    List<Customer> selectList(@Param("keyword") String keyword);

    Customer selectById(@Param("id") Long id);

    int insert(Customer customer);
}
