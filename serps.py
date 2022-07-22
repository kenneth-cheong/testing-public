from selenium import webdriver
from webdriver_manager.chrome import ChromeDriverManager
from webdriver_manager.core.utils import ChromeType
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
import time
import pandas as pd
import collections

chrome_service = Service(ChromeDriverManager(chrome_type=ChromeType.CHROMIUM).install())

chrome_options = Options()
options = [
    "--headless",
    "--disable-gpu",
    "--window-size=1920,1200",
    "--ignore-certificate-errors",
    "--disable-extensions",
    "--no-sandbox",
    "--disable-dev-shm-usage"
]
for option in options:
    chrome_options.add_argument(option)

driver = webdriver.Chrome(service=chrome_service, options=chrome_options)

keywords = ['seo marketing','sem marketing']

targeted_url = 'mediaone'

ranking_list = []

for i in keywords:
    i.replace(' ','+')
    driver.get('https://www.google.com/search?q='+i+"&cr=countrysg&pws=0&num=100")
    time.sleep(180)
    
    list_1 = []
    list_2 = []
    list_3 = []

    for i in range(1,101):
        try:
            domain = ((driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[1]/div/a/div/cite').text).split(''))[0]
            title = driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[1]/div/a/h3').text
            desc = driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[2]').text
            if desc == '':
                 desc = driver.find_element(By.XPATH,'//*[@id="rso"]/div['+str(i)+']/div/div[3]').text
            list_1.append(domain)
            list_2.append(title)
            list_3.append(desc)
        except:
            pass

    df = pd.DataFrame(list(zip(list_1,list_2,list_3)), columns =['domain','meta_title','meta_desc'])
    df.index += 1
    
    try:
        ranking_list.append(((df.index[df['domain'].str.contains(targeted_url)]).tolist())[0])
    except:
        ranking_list.append('not found')

df_kws_rankings = pd.DataFrame(list(zip(keywords,ranking_list)), columns =['keyword','position'])

df_kws_rankings.to_csv(targeted_url+'_rankings.csv')

df.to_csv(targeted_url+'_serps.csv')
