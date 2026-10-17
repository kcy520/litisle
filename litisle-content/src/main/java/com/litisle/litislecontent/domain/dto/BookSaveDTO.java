package com.litisle.litislecontent.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 书籍创建/编辑入参（BookService#create / #update 共用）
 * <p>status（连载中/完结/下架）不在此 DTO——状态流转走独立接口，防编辑接口越权改状态</p>
 *
 * @author litisle
 */
@Data
@Accessors(chain = true)
public class BookSaveDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 书名（≤100） */
    @NotBlank(message = "书名不能为空")
    @Size(max = 100, message = "书名长度不能超过 100 个字符")
    private String title;

    /** 简介（≤500，可空） */
    @Size(max = 500, message = "简介长度不能超过 500 个字符")
    private String intro;

    /** 封面文件ID（SeaweedFS，可空） */
    @Size(max = 64, message = "封面文件ID长度不能超过 64 个字符")
    private String coverFileId;

    /** 分类（单选，默认"小说"） */
    @Size(max = 20, message = "分类长度不能超过 20 个字符")
    private String category;
}